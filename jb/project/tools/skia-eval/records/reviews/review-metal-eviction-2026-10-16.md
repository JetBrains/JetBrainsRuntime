# Review: Metal glyph cache per-cell eviction (JBR-10667)

Date: 2026-10-16. Worktree `build/wt-metal`, branch `manuel.unterhofer/JBR-10667-metal-glyph-cache`.
Scope: HEAD `f2dc70c4fe94` (3 files: no-wait commit, 1024x1024 caches, plain layout) plus the
uncommitted diff (7 files: per-cell eviction, command buffer sequence counter), reviewed as one
change. Every statement below was checked against the code in the worktree; points I could not
settle from the sources are marked **to verify** with what would settle them. Severity: **bug**
(wrong today), **risk** (wrong under a reachable condition, or sound only under an unstated
assumption), **nit** (quality).

Files: `src/java.desktop/macosx/native/libawt_lwawt/java2d/metal/` -- `MTLGlyphCache.{h,m}`,
`MTLTextRenderer.m`, `MTLContext.{h,m}`, `MTLLayer.m`, `MTLBlitLoops.m`, `MTLTexturePool.m`.

---

## Summary of findings

| # | Where | Severity | One line |
|---|-------|----------|----------|
| B1 | `MTLGlyphCache.m:263-286` with `:247-252` | **bug** | `isCacheFull:` derives fullness from `tail`; `reusableCell` rotates the list, so after the first eviction the cache reports "not full" and `addGlyph` allocates a new cell on top of an existing one. |
| B2 | `MTLGlyphCache.m:93-101` | **bug** | `cellCount` is never initialised (malloc garbage); the `2 * cellCount` bound of the eviction walk is undefined. |
| B3 | `MTLTextRenderer.m:225` (+ HEAD `MTLGlyphCache.m:111-116`) | **bug** (reachable only on texture allocation failure) | After the wholesale fallback, `[gc addGlyph:glyph]` runs even if re-validation failed and `gc.cacheInfo == NULL`; HEAD made that state reachable. |
| R1 | `MTLTextRenderer.m:108-114` vs `MTLRenderQueue.m:694-696, 711-713, 729-731` | **risk** | `free` now commits inside `Flush`, so the `commitCommandBuffer:YES` that JBR-6723 placed after the frees in FLUSH_SURFACE / DISPOSE_SURFACE / DISPOSE_CONFIG finds no wrapper and no longer waits. Combined with the new retain of the context in the completion blocks, `MTLContext.dealloc` can run on a Metal completion thread. |
| R2 | `MTLContext.m:774-779` | **risk** (low) | The max-CAS counter is correct only if command buffers of one queue complete in commit order. Standard Metal premise; state it as such in the header comment. |
| R3 | `MTLGlyphCache.m:235-261` | **risk** (performance) | When the cache is full and most cells were drawn in the current frame, every add walks up to `2 * cellCount` (2048) nodes before falling back. Bounded, but O(cells) per glyph in exactly the scene the change targets. |
| N1-N9 | various | nit | See section 7. |

Areas found clean: the `lastUsed <= completed` test itself (section 1), the set of commit sites
(section 1), the atomic (section 2), the list integrity of the walk apart from B1/B2 (section 3),
threading (section 6), the `allowGPUOptimizedContents` placement (section 7).

---

## 1. Memory and GPU safety of cell reuse

**The test is sufficient, given B1 and B2 are fixed.** Reasoning verified against the code:

- `_commandBufferSequence` is the number of the buffer being recorded; it is incremented in
  `pullCommandBufferWrapper` (`MTLContext.m:757-763`) only when a wrapper actually existed, and
  every pulled wrapper is committed by its caller (`MTLContext.m:1049-1056`, `MTLLayer.m:491-511`,
  `MTLBlitLoops.m:863-870`; the only other `pullCommandBufferWrapper` is in commented-out code,
  `MTLRenderQueue.m:613-618`). So sequence numbers are consecutive and each one is committed
  exactly once, and `completed <= _commandBufferSequence - 1` always: a cell stamped in the
  current buffer is never idle.
- Encoders are created only through `EncoderManager` (`EncoderManager.m:398, 451`), which pulls
  the buffer from `getCommandBufferWrapper`, so `cacheInfo->encoder` always belongs to the wrapper
  with the current sequence at the time `MTLTR_ValidateGlyphCache` ran.
- Grayscale path: `cell->lastUsed = [mtlc commandBufferSequence]` (`MTLTextRenderer.m:361`) is
  set before `MTLVertexCache_AddGlyphQuad`. Quads are flushed into `cacheInfo->encoder` either
  when the vertex array fills (`MTLVertexCache.m:467-470`, same buffer) or from
  `MTLTR_DisableGlyphVertexCache`, which runs on every mode switch and at the end of
  `MTLTR_DrawGlyphList` (`:757-767`). Within one glyph list the only commit is the wholesale
  fallback in `MTLTR_AddToGlyphCache`, which is preceded by `MTLTR_DisableGlyphVertexCache`
  (flush into the old buffer) and followed by `MTLTR_EnableGlyphVertexCache` (new encoder in the
  new buffer); the stamp is taken after `MTLTR_AddToGlyphCache` returns, so it carries the new
  sequence. Correct.
- LCD path: `MTLTR_DrawLCDGlyphViaCache` encodes immediately into
  `mtlc.glyphCacheLCD.cacheInfo->encoder` (`:423-435`); stamp at `:421` is taken after the
  possible fallback. Correct.
- Every draw that samples a cell passes a stamp: the only two samplers of the glyph textures are
  `MTLVertexCache_FlushGlyphVertexCache` (fed solely by `MTLTR_DrawGrayscaleGlyphViaCache`,
  which stamps the cell it enqueues, including the "take first cell anyway" subimage fallback at
  `:357`) and `MTLTR_DrawLCDGlyphViaCache`. No other code references `cacheInfo->texture`.
- Command buffers created outside the wrapper (`MTLLayer.blitTexture` via
  `createBlitCommandBuffer`, `MTLLayer.flushBuffer`, `MTLBlitLoops_SurfaceToSwBlit` `:752`,
  the swizzle compute buffer `:200`, `MTLBufImgOps.m:160` A8 texture) never bind a glyph cache
  texture and consume no sequence number. Fine.
- Why no `Flush` before evicting an occupied cell, unlike `AccelGlyphCache.c:224-229`: there the
  upload is GPU-ordered and the pending vertex array must be drawn before the texel upload is
  enqueued. Here a cell with pending quads has `lastUsed == current > completed` and is never
  chosen, so the flush is unnecessary. Correct; worth one sentence in the `reusableCell` comment.

**Pre-existing hazard, unchanged by this change (note only):** `MTLTR_DrawGlyphList:717-719`
commits the command buffer before the first LCD glyph of a list; if the list was in
`MODE_USE_CACHE_GRAY`, the mode switch inside `MTLTR_DrawLCDGlyphViaCache` then flushes the
glyph vertex cache into the already-ended encoder. A mixed gray/LCD list from one strike does not
occur in practice, but if it did, the quads would carry a stale stamp. Not introduced here.

**To verify (documentation):** `replaceRegion:` on a texture while another region of the same
texture is sampled by an in-flight buffer. The pipeline has always done this for never-used cells;
the change makes it routine for reused cells. Apple's `replaceRegion` discussion says the method
does not synchronize against GPU access; the sequence test guarantees that the texels being
written have no pending reads, which is the intent, and the plain layout
(`allowGPUOptimizedContents = NO`) removes any shared compression metadata between cells. State
this reasoning in the `MTLGlyphCache.h` comment on `lastUsed` so a reviewer sees it was considered.

## 2. The completion counter

Clean as code. `_completedCommandBufferSequence` is `_Atomic(uint64_t)` (`MTLContext.m:199`),
initialised with `atomic_init` (`:460`), written only by `commandBufferCompleted:` on Metal's
completion threads, read with `atomic_load` on the render thread (`:770-772`). The CAS loop
(`:774-779`) is correct: on a failed `atomic_compare_exchange_weak`, `current` is reloaded and the
`sequence > current` guard is re-evaluated, so it terminates and never lowers the value.
Default seq_cst is fine; one load per `reusableCell` call.

Initial values: `_commandBufferSequence = 1`, completed `0`, new cells `lastUsed = 0`
(`MTLGlyphCache.m:179`) -> a never-drawn cell is idle; a cell drawn in the first buffer has
`lastUsed = 1 > 0`. Consistent. A reused cell keeps its old `lastUsed` (<= completed by
construction) until its next draw. Fine.

**R2 -- `MTLContext.m:775`, risk (low).** Taking the maximum assumes that buffer N+1 completing
implies buffer N completed, i.e. in-order execution of one `MTLCommandQueue`. That is the standard
Metal guarantee (one queue executes committed buffers serially in commit order) and the comment
says so; the alternative (tracking the lowest outstanding sequence) is not worth its complexity.
Suggest moving the sentence into the `MTLContext.h` block comment (`:114-118`), which is where a
reader of `completedCommandBufferSequence` will look.

## 3. The eviction walk (`reusableCell`, `MTLGlyphCache.m:235-261`)

**B1 -- `MTLGlyphCache.m:263-286` with `:247-252`, bug.** `isCacheFull:` computes the next free
position from `_cacheInfo->tail` (`:275-283`). Before this change the list was append-only, so
`tail` was always the last allocated cell and the computation was exact. `reusableCell` now moves
every visited cell, including the chosen one, to the tail. Sequence after the first eviction:
the chosen cell (say the original head at (0,0)) becomes `tail`; the next `addGlyph` calls
`isCacheFull:` -> `x = 32, y = 0` -> not full -> `malloc` of a new cell at (32,0), which is the
position of the second existing cell. Two live cells share texels (glyph corruption on the next
upload of either), `cellCount` and the list grow without bound, and the `MTLTR_AddToGlyphCache`
fallback condition `ccinfo == NULL && [gc isCacheFull:glyph]` is equally wrong. Fix: make fullness
sticky as `AccelGlyphCache` does (`AccelGlyphCache.c:121-139`, `isFull` field): add
`jboolean isFull` to `MTLGlyphCacheInfo`, set it when the cell at the last position is allocated
(or when `isCacheFull:` first returns true), and have `isCacheFull:` return it; or compute it as
`cellCount == (width / cellWidth) * (height / cellHeight)` once B2 is fixed. The walk itself
(head moves to `current->next`, old tail linked to `current`, `current->next = NULL`) keeps the
singly linked list intact, and the one-cell case (`current->next == NULL`, no move) is handled.

**B2 -- `MTLGlyphCache.m:93-101`, bug.** `glyphCacheInitWidth` fills every field of the
malloc'ed `MTLGlyphCacheInfo` except the new `cellCount`. The bound `2 * _cacheInfo->cellCount`
(`:241`) therefore starts from garbage: too small -> premature `NULL` and a needless wholesale
replacement; huge or negative after `jint` overflow -> the loop either runs long or not at all.
Fix: `_cacheInfo->cellCount = 0;` next to `head`/`tail`. Alternatively drop the field: when
`reusableCell` is called the cache is full and the list has exactly
`(width / cellWidth) * (height / cellHeight)` cells, so the bound can be computed, and B1's
sticky-full flag makes the field unnecessary for anything else.

Termination and policy (with B1/B2 fixed): each iteration rotates by one, so `cellCount`
iterations are one full pass; the first pass zeroes the counters of every idle cell that was not
chosen, the second pass picks the first idle cell. Equivalent to `AccelGlyphCache.c:203-221`
except that busy cells are skipped without a counter reset, as documented. Correct.

`MTLGlyphCache_RemoveCellInfo` and the `nextGCI` chain: the reused cell is unlinked from its old
glyph (`:197-201`) before `MTLGlyphCache_AddCellInfo` links it to the new one (`:216`); the
shared `AccelGlyphCache_RemoveAllCellInfos` (called from `AWTStrike.m:703` on glyph disposal)
only touches `glyphInfo` and `nextGCI`, whose offsets are identical in `CacheCellInfo` and
`MTLCacheCellInfo` (both are the first four pointer fields; `lastUsed` was inserted after
`timesRendered`, `MTLGlyphCache.h:71`, so the shared code is unaffected). A disposed glyph's
cell has `glyphInfo == NULL` and is chosen first when idle. Correct.

Subpixel glyphs: a glyph with `subpixelResolution > 1` owns several cells (one per `glyphSubimage`);
evicting one of them removes just that cell from the chain, and the lookup in
`MTLTR_DrawGrayscaleGlyphViaCache:331-340` misses only that subimage and re-adds it. The reuse path
may evict another subimage of the very glyph being added; the chain stays consistent because the
unlink happens before the link. `addGlyph` resets `glyphSubimage = 0` and the caller sets the
real subimage (`MTLTextRenderer.m:235`). Correct.

`free` (`:292-318`): unchanged in structure; it walks from `head`, which is still the full list
after rotations. Fine.

**R3 -- performance, `MTLGlyphCache.m:241`.** In a frame that draws more distinct glyphs than
cells, once the cache is full every further `addGlyph` walks the whole list twice (2048 steps
for 1024 cells, pointer-chasing) before returning `NULL` and falling back. That is bounded and
still far cheaper than the texture reallocation that follows, but it is paid once per new glyph
until the fallback resets the cache. Optional mitigation: stop after the first full pass if no
idle cell was seen (track `sawIdle`), halving the worst case; or remember the first busy-skipped
run. Not required for the PR.

## 4. The fallback in `MTLTR_AddToGlyphCache` (`MTLTextRenderer.m:211-226`)

Identity: `gc` is `mtlc.glyphCacheAA` / `glyphCacheLCD`, an `MTLGlyphCache` object that survives
`free` (`free` releases `_cacheInfo` and sets it to `NULL`, `MTLGlyphCache.m:316-317`;
`glyphCacheInitWidth` allocates a new one on the same object). So `[gc addGlyph:glyph]` at `:225`
addresses the right object and the fresh `cacheInfo`. The `encoder` field is refreshed by
`MTLTR_ValidateGlyphCache:139-141` on both branches; the grayscale branch also re-creates the
vertex array (`MTLTR_DisableGlyphVertexCache` frees it, `MTLTR_EnableGlyphVertexCache` re-inits).
Consistent.

**B3 -- `MTLTextRenderer.m:225`, bug (reachable only when texture allocation fails).** HEAD added
the `texture == nil -> free(_cacheInfo); _cacheInfo = NULL; return NO` path
(`MTLGlyphCache.m:111-116`). `MTLTR_ValidateGlyphCache` then returns `JNI_FALSE`, both fallback
branches ignore that, and `[gc addGlyph:glyph]` dereferences `_cacheInfo->cellWidth` with
`_cacheInfo == NULL`. Before HEAD a nil texture left a non-NULL `cacheInfo` and `replaceRegion`
on nil was a no-op, so the NULL dereference is new. Fix: guard the second call,
`ccinfo = (gc.cacheInfo != NULL) ? [gc addGlyph:glyph] : NULL;` (or `return` when re-validation
fails). The pre-existing early return at `:207-209` already shows the intended behaviour.

Note that the `isCacheFull:` test at `:212` also returns true for an over-sized glyph (`addGlyph`
returns `NULL` at `:150-154` for `width > cellWidth`), which would trigger a needless wholesale
replacement; the caller filters glyph size first (`:698-700, 740-742`), so it is unreachable from
`MTLTR_DrawGlyphList`. Same as before the change.

## 5. The stamp sites

`[mtlc commandBufferSequence]` is one `objc_msgSend` per glyph, next to the several already on
that path (`mtlc.glyphCacheAA`, `.cacheInfo`, `MTLVertexCache_AddGlyphQuad`'s traces). Acceptable.
A cheaper and slightly more principled variant: record the sequence next to the encoder it belongs
to, `cacheInfo->sequence = [mtlc commandBufferSequence]` in `MTLTR_ValidateGlyphCache:139` right
after fetching the encoder, and stamp `cell->lastUsed = cell->cacheInfo->sequence`. That is a
field read per glyph and ties the stamp to the buffer the encoder actually records into, which is
what the invariant is about. Optional.

## 6. Thread-safety assumptions

Clean. The glyph caches, `_commandBufferWrapper` and `_commandBufferSequence` are touched only
from the render-queue flusher (`MTLRenderQueue_flushBuffer` and what it calls; `MTLLayer`'s own
command buffers go through `createBlitCommandBuffer`/`createCommandBuffer`, not the wrapper), i.e.
under the Java `RenderQueue` lock, which orders accesses even if the flushing thread changes. The
plain `uint64_t` for `_commandBufferSequence` is therefore right; only the completion counter
crosses threads and it is atomic. `MTLCommandBufferWrapper.sequence` is immutable after init.

## 7. Code quality

**R1 -- `MTLTextRenderer.m:108-114` with `MTLRenderQueue.m:694-696, 711-713, 729-731`, risk.**
`MTLTR_CommitGlyphVertexCache` (the `Flush` of both caches) now does `commitCommandBuffer:NO`.
JBR-6723 (`ed6074e5858a`) placed `[mtlc commitCommandBuffer:YES display:NO]` *after*
`[glyphCacheAA free]; [glyphCacheLCD free];` in FLUSH_SURFACE, DISPOSE_SURFACE and
DISPOSE_CONFIG (the DISPOSE_CONFIG hunk moves it from before to after the frees). With this
change, whenever a cache exists at that point, `free` commits first and the following
`commitCommandBuffer:YES` finds `_commandBufferWrapper == nil` and does not wait; whether the
dispose waits now depends on whether text was drawn since the last `free`. Memory-wise the
surface texture is retained by the committed buffer, so `MTLSD_Delete` is safe; but
DISPOSE_CONFIG then releases the context (`MTLGraphicsConfig.m:49-53`, on the main thread) while
the completion blocks of this change hold a strong reference to it (`[self ...]` in
`MTLContext.m:1053`, `[mtlc ...]` in `MTLLayer.m:498`; the old blocks captured only `cbwrapper`).
The last release can then happen in the completion handler and `MTLContext.dealloc` runs on a
Metal thread: `haltRedraw` asserts the AppKit thread (`MTLContext.m:1219`;
`AWT_ASSERT_APPKIT_THREAD` is a message, a spin-loop or a no-op depending on the build,
`ThreadUtilities.h:88-124`) and `_layers` is mutated off the main thread. Rare (display
configuration changes) but new.
Fix, preferred: drop the commit from `MTLTR_CommitGlyphVertexCache` and keep only the flush (rename
back to a flush). A command buffer created by `[MTLCommandQueue commandBuffer]` retains the
resources its encoders reference from the moment they are bound, so the texture is kept alive by
the flushed draw without a commit; the texture `release` in `free` only drops our reference. This
also removes one command buffer split per wholesale replacement and restores the dispose-op waits
exactly as JBR-6723 left them. **To verify:** that the `commitCommandBuffer:YES` of JBR-6723 was
not covering a second symptom (the issue is a flusher crash in `MTLTR_DrawGlyphList`; the 38
comments or its author would settle it). Fallback fix if the commit must stay: make the three
dispose ops wait explicitly (e.g. `[[[mtlc getCommandBufferWrapper] getCommandBuffer] ...]` is not
available after the pull; simpler is a `free` variant that does not commit, used by those ops).

Nits:

- **N1 -- commit message of HEAD.** It states that per-cell eviction "is left for a follow-up";
  the working tree adds it. The PR (squashed or as two commits) needs the message updated, and the
  perf table should say which configuration it measured (the message's numbers predate the
  eviction).
- **N2 -- `MTLGlyphCache.h:101` `- (void) invalidate;`** is declared and not implemented anywhere
  (`grep invalidate MTL*.m` finds nothing). Pre-existing; removing it while editing this header is
  reasonable and a reviewer will ask.
- **N3 -- `MTLContext.m:25` `#include <stdatomic.h>`.** Fine: `.m` files are compiled as
  Objective-C with clang (`CompileFile.gmk:146`, `-x objective-c`) under `-std=c11`
  (`flags-cflags.m4:608`), and `ThreadUtilities.h:30` already imports it in this library. No
  warning flags concern. Consider `#import` for consistency with the neighbouring import, or keep
  `#include` as the file does for `<stdlib.h>` -- either is defensible; the file mixes both.
- **N4 -- the 1024 constant (`MTLTextRenderer.m:45-46`).** 1 MB A8 + 4 MB BGRA per context,
  reallocated after every `free`. Since `SET_SURFACES` and `SET_SCRATCH_SURFACE` free both caches
  (`MTLRenderQueue.m:652-653, 674-675`, upstream OpenJDK behaviour), a 4 MB `newTextureWithDescriptor`
  now happens on the first LCD text after every surface switch. The measurements cover this, but
  the PR description should say that eviction only helps within one surface stint and that
  removing the per-`SET_SURFACES` free (the `encoder` field is what ties the cache to `dstOps`,
  and `MTLTR_ValidateGlyphCache` refetches it anyway) is the natural next step. Also:
  `MTLTR_NOCACHE_TILE_SIZE` and the cell size stay 32 -- fine, only the atlas grew.
- **N5 -- `allowGPUOptimizedContents` placement (`MTLTexturePool.m:83-87`).** Correct scope: the
  only pool client requesting `MTLPixelFormatA8Unorm` is the mask cache
  (`MTLVertexCache.m:245`); `MTLBufImgOps.m:160` creates its A8 texture outside the pool; the
  glyph caches set the flag themselves (`MTLGlyphCache.m:109`). The pool keys items by format, so
  no other consumer receives these textures. Clean. The comment "A8 textures from the pool are the
  mask cache" is a claim about callers that will rot silently; phrase it as the reason
  ("filled by replaceRegion") and drop the caller name, or add the flag as a parameter.
- **N6 -- comment style.** `MTLContext.h:114-118` and the `MTLGlyphCache.h:68-71` comments carry
  the why; good. `MTLContext.m:198-199` end-of-line comments on the ivars duplicate the header;
  keep one. The `reusableCell` doc comment (`MTLGlyphCache.m:221-234`) is long for a method whose
  body is 25 lines; the policy paragraph duplicates `AccelGlyphCache.c:177-201` -- a one-line
  reference plus the Metal-specific paragraph would do.
- **N7 -- empty loop body `{ }` (`MTLContext.m:777-778`).** Reviewers will prefer the body on one
  line with a `;` and a comment, or a `do { } while` form that reads as a retry loop.
- **N8 -- trace levels.** `J2dRlsTraceLn(J2D_TRACE_ERROR, ...)` for the texture failure
  (`MTLGlyphCache.m:112`) is right; the per-glyph `J2dTraceLn(J2D_TRACE_INFO, "MTLGlyphCache.addGlyph")`
  and `"isCacheFull"` are pre-existing. Nothing new at the wrong level.
- **N9 -- naming.** `commandBufferCompleted:` beside the wrapper's `onComplete` and
  `MTLContext.commitCommandBuffer:` reads fine; `reusableCell` as a public method
  (`MTLGlyphCache.h:99`) is only used internally -- make it a static function or a class-extension
  method so the header does not grow API. `MTLTR_CommitGlyphVertexCache` is accurate for what it
  does today; under the R1 fix it becomes a flush again and the JBR-6723 name can be dropped
  entirely in favour of passing `MTLVertexCache_FlushGlyphVertexCache` directly (as the code did
  before JBR-6723).

## Verdict

Not ready for a pull request to the Metal pipeline owners as it stands. B1 is a correctness bug
that corrupts glyphs and leaks cells after the first eviction, i.e. in the first text-heavy frame
with a full cache; B2 makes the walk bound undefined; both are a few lines to fix (sticky `isFull`
flag mirroring `AccelGlyphCache`, `cellCount = 0` or no `cellCount` at all). B3 is a guard.
R1 needs a decision -- I recommend removing the commit from the `Flush` function, which also
simplifies the story in the commit message (flush only; the buffer retains the texture) -- and a
sentence in the PR about the retained context in the completion blocks. With those four addressed
and the commit message updated (N1), the design is sound: the sequence invariant is verified
end-to-end against every commit site, the atomic is correct, the threading model is unchanged,
and the rest is nits.
