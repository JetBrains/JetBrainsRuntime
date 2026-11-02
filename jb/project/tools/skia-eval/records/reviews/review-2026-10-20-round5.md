# Review round 5 of `manuel.unterhofer/JBR-10654-skia-backend` (HEAD 00c0f47f1da2)

Date: 2026-10-20. Scope: the two commits cherry-picked from the Windows VM, `b4dd1cc3e83a` (fixup of
the two-pass commit: mesh layers instead of ending the batch on overlap, ink extents per atlas cell)
and `00c0f47f1da2` (LCD coverage table, `accumulate` mode, blender split gated on framebuffer fetch,
`LcdMode` enum), read with `git show` and then as whole functions at HEAD together with their callers
(`SKQuadBatch.h`, `SKGlyphCache.{h,cpp}`, `SKMaskCache.cpp`, `SKBackend.{h,cpp}`, `SKEnvironment.{java,cpp}`,
`SKRenderQueue.cpp`, `SKSurfaceData.java`, `SKContext.java`, `MTLSKEnvironment.mm`, `DawnSKEnvironment.cpp`,
the two jtreg tests, the m156 `Surface.h`/`Recorder.h` headers, the ledger's Graphite rules). The
Dawn measurements and the Metal runs of the jtreg tests, the render matrix and the window scene are
the author's and are not repeated; this round looks for what they do not cover. Severity as before:
**bug** (wrong today under a reachable condition), **risk** (latent, or correct on an unstated
assumption), **nit**.

---

## 1. Layers (`SKQuadBatch.h:210-313, 347-370`)

**Separation holds, order does not.** A quad is appended to the first mesh (base, then the layers in
order) in which it overlaps nothing (`:284-297`); within a mesh no two quads overlap, by
construction. But a quad is not drawn after everything it overlaps: with A in the base, B in layer 1
(overlaps A) and C overlapping only B, `base.overlaps(C)` is false (`:284`) and C lands in the base,
which is drawn *before* layer 1 (`:359-362`), i.e. B is composited over C although C was submitted
after B. The same happens for any quad that overlaps only quads of a later layer. The comments say
the opposite ("the layers are drawn in order", `:213`; "a quad that overlaps a pending one starts a
new draw", `:90-91`).

**F3 -- risk: order is lost in transitive chains; the result is right only because every operation
batched under `splitOnOverlap` is commutative for quads of one paint.** XOR (`dst ^ k1 ^ k2`, `:786-838`),
the LCD gamma blend of one colour (two interpolations toward the same target `Cs^g` commute), and the
two passes of one colour (`s*w1(1-w2) + s*w2 = s*w2(1-w1) + s*w1`) all give the same pixel in either
order; `mergeColors` is never true under `splitOnOverlap` (`:94-99`: a blender has no blend mode,
passes exclude merging), so one batch is one paint and one colour. That is why the three-copy
overlap test passes. Nothing in the file states the invariant that is actually relied on
(separation, not order), and a future non-commutative use (a per-vertex-coloured mesh, a blend mode
with a dst read, a `SrcOver` layer) would break silently. *Fix:* either document it at `:210` ("a
quad is drawn in some mesh after no quad it overlaps shares; the order between meshes is not the
submission order, which is sound because ... commute"), or preserve order cheaply: put a quad into
the first layer *after the last layer it overlaps* (scan from the top: find the highest mesh it
overlaps, use the next one), which keeps every quad after everything it overlaps at the cost of
testing every layer instead of stopping at the first free one.

**Hashed grid.** `cells()` (`:236-244`) visits every 64-px cell a rect touches, `addBounds` registers
the index in each cell's bucket, `overlaps` tests every bucket of every cell against `bounds[index]`
with `SkRect::Intersects` (`:252-260`). Two intersecting rects share at least one cell and therefore
a bucket, so a hash collision only adds candidates, never hides one: **no false negatives.** The
`ceilf(fRight) - 1` end cell is right for integer edges ([0, 64) is cell 0 only); negative
coordinates take the arithmetic shift (floor division) and `& 63` on a negative hash is fine in
two's complement. `Intersects` is strict, so touching glyph boxes do not split (as intended). The
only cost is duplicate candidates (a rect spanning many cells is pushed once per cell and may be
tested several times); harmless at glyph size, see F12 for large XOR rects.

**Bounds past 1024 and 65535.** `kMaxOverlapTested` (`:265, :280-282`) draws base and *all* layers
when the base holds 1024 *registered* bounds, so no pending quad is lost and the separation is kept;
the triggering quad starts a fresh base. Two counts are not bounded, however:

**F4 -- risk (latent): 16-bit indices are not checked under `splitOnOverlap`.** The `0xffff` test
is in the `else` branch (`:299-300`), so it never runs for a blender or two-pass batch. (a) Quads
with empty bounds (a glyph whose every pixel is <= 8, a degenerate parallelogram side) go to the base
without `addBounds` (`:283, :297`), so they do not count toward 1024 and could in principle push
`base.positions` past 65535. (b) A layer's size is bounded only by the area argument (non-overlapping
ink inside <= 1024 base quads), not by code; `first` (`:302`) and the `uint16_t` cast in
`addBounds` (`:247`) would wrap. Neither is reachable with real text (it needs > 16k quads in one
layer or > 15k empty-ink glyphs without a flush), hence latent. *Fix:* after choosing `target`,
`if (target->positions.size() + 4 > 0xffff) drawPending();` and re-choose (or simply count every
quad toward `kMaxOverlapTested`).

**Reset and memory.** `drawPending` clears `base` (positions, texCoords, indices, bounds, all 64
buckets), `layers`, `colors` (`:367-369`); `colorPerVertex`/`color` are per batch and reset in
`begin()`. Nothing grows across draws (`pendingBounds` is gone). `layers.clear()` destroys the
`Mesh` objects, so every layer is re-allocated at the next draw (68 vectors per `Mesh`, 64 of them
the buckets); the VM measured this and it was fine, see F12.

## 2. Ink extents (`SKGlyphCache.cpp:47-49, 74-93, 159-183, 206`; `SKQuadBatch.h:152-165, 270-279`)

- Stored per cell (`ink[cellIndex(cell)]`, 1024 entries for the 32x32 grid, `:126, :70-72`),
  written in `upload()` for every cell `AccelGlyphCache_AddGlyph` hands out, new or evicted
  (`:165, :169`), from the same bytes the atlas receives (`data` for the A8 sub-image, the strike's
  RGB triples for LCD). A freed glyph leaves a stale entry behind, but no sprite references a free
  cell. The pointer passed to `addSprite` is into a vector that is sized once (`:126`) and never
  resized: stable.
- Sub-pixel variants: one cell per sub-image, and the A8 extent is computed from
  `image + rowBytes * h * subimage` (`:162-165`). LCD glyphs are cached only with `subimage == 0`
  and `rowBytesOffset == 0` (`SKBackend.cpp:1662-1663`); the shifted ones go uncached. Correct.
- Used only under two passes: `push()` takes the extent only `if (extent != nullptr && twoPass != nullptr)`
  (`SKQuadBatch.h:275`); the blender (whole quad, as the VM found), the AA cache under XOR, the mask
  cache and `gRects` all use the quad bounds. Under `accumulate` `splitOnOverlap` is false (kScreen
  is a blend mode, no passes), so the extent is unused. Correct.

**F2 -- risk (fidelity): the 8/255 threshold is applied to the raw coverage, but the error it admits
is governed by the *corrected* coverage, which is much larger for small values.** With
`w = lcdCorrectedCoverage(8/255, src, gamma)` (`SKBackend.cpp:1311-1317`): for white text at the
default contrast 140 (`1/gamma = 0.714`) `onBlack = 0.0314^0.714 = 0.084`; at contrast 250 it is
`0.0314^0.4 = 0.25`; at 100 it is 0.031. Two glyphs whose ink extents do not intersect share a mesh,
and where a padding pixel of one (coverage <= 8) lies on the other's ink the mesh adds
`s * w1 * w2` too much (round 4, F2): for white on dark with a 50 % edge under the padding pixel
that is `0.5 * 0.084 = 0.042` (11 levels) at 140 and `0.5 * 0.25 = 0.125` (32 levels) at 250; for
mid-gray text about 7 levels at 140. Adjacent LCD glyph boxes overlap by the filter's padding on
both sides by construction (ledger §11.20), so a padding pixel over a neighbour's stem edge is the
normal case in tight kerning. This is consistent with the numbers in the commit message: two-pass
had "<= 15 levels" with whole-box splitting (round 4) and reports **max 25** now, while `accumulate`
(no sharing) is at 15. Not a wrong pixel in the sense of a bug, but it eats most of the test's
32-level tolerance at the default contrast and exceeds it at high contrast. *Fix:* threshold the
*corrected* value (compute the extent with `lcdCorrectedCoverage(v/255, 1, gamma) * 255 <= k`, i.e.
raw threshold `(k/255)^gamma`: 1-2 at 140 for `k = 8`), or use threshold 0 (the true support) and
measure the extra layers with `LcdTextBench`, since a layer costs one draw pair and no copy now.
**To verify:** `LcdTextBackgroundsTest` in `twopass` with `INK_THRESHOLD = 0` or `1`; if the max
drops from 25 toward 15, the threshold is the cause.

## 3. Blender gate (`SKBackend.cpp:101-111`; `SKGlyphCache.cpp:127`; `SKMaskCache.cpp:93`)

- Threaded correctly: `framebufferFetchNative()` (Metal: `supportsFamily:MTLGPUFamilyApple1`,
  `MTLSKEnvironment.mm:59-70`, closing round-4 F4; Dawn: `JNI_FALSE`, `DawnSKEnvironment.cpp:57-64`)
  -> `defaultsFor` -> the `Configuration` record carries it -> `configureNative(..., ordinal, fbf)`
  (`SKEnvironment.java:166-168`) -> `(SKLcdMode) lcdMode, framebufferFetch == JNI_TRUE`
  (`SKEnvironment.cpp:39`) -> `gConfig.framebufferFetch` and `gRects.splitBlenders` (`:105-106`).
  `gRects` is a file static (now defined above `SKBackend_Configure`, `:99`) and defaults to
  `splitBlenders = true`; `configureNative` runs in `SKEnvironment.init()` before any configuration
  exists, and the glyph and mask caches read the flag at creation, which is after the first context.
  All three batch kinds are gated.
- Metal order within one draw: Graphite with `fFBFetchSupport` reads the destination as a
  `[[color(0)]]` input and does not break the pass; Metal serialises fragments of one pixel in
  primitive order for programmable blending. The tree's own evidence is the ledger's matrix cell
  `comp:XOR/STROKE_W5` (§953): when both parallelograms shared one mesh, Metal XORed the crossing
  twice (correct) and Dawn once. So under the gate Metal is back to that state, on purpose. Between
  draws Graphite keeps painter's order for draws that read the destination. No finding.

## 4. Accumulate (`SKBackend.cpp:1412-1492, 1562-1668`; `SKQuadBatch.h:192-200, 347-370`)

**Flush order.** `beforeDraw` (clear), the sprite draw into the scratch and `afterDraw` (composite)
run inside one `drawPending()` call (`SKQuadBatch.h:356-365`), so no mask or rect batch can slip
between coverage and composite. Relative to other primitives the LCD batch is flushed by the
`REQUIRE_CANVAS*` macros before anything else touches the canvas (`:890-893`) and by
`SKBackend_FlushRecording` before a snap (`:1986-1989`). The sprites go to the scratch's canvas
unclipped (device space, identity), the composite goes to `g.canvas` under the destination's clip
and matrix, which at glyph-list time is the device clip and identity (text validates without a
transform); `applyCanvasState` flushes *before* it changes the clip (`:349`), so a pending batch is
composited under the clip it was queued with. Correct. Ordering of the scratch's draws against the
composite that samples it, and of the next batch's clear against that composite, is Graphite's
dependency tracking through `SkSurfaces::AsImage` (the ledger's rule for the back-buffer blit,
§559; uploads are the exception, §11.13 rule 3). **To verify** only in the sense that no test here
draws two LCD colours overlapping in one recording under `accumulate` (the window scene does, and
passes on Metal).

**F1 -- bug (accumulate only): the draw hooks read the *current* render state (`g.cc`, `g.canvas`)
instead of the batch's, and one opcode nulls the canvas before the flush.** `clearLcdCoverage` and
`compositeLcdCoverage` use `g.cc->lcdCoverage` and `g.canvas` (`:1434-1445`, lambdas `:1462-1465`).
`DISPOSE_SURFACE` for the current destination calls `SKBackend_InvalidateState()` (`g.canvas = nullptr`,
`SKRenderQueue.cpp:281-283`, `SKBackend.cpp:423-426`) and only then `SKBackend_ReleaseSurface`,
whose `flushAllBatches()` (`:270`) draws the pending LCD batch: the sprites still land in the scratch
(the batch holds that canvas), then `afterDraw` dereferences the null `g.canvas` in `drawLcdPasses`
(`:1406`). In `blend`/`twopass` the batch draws on its own canvas pointer and this path is safe.
Reachable: `SKSurfaceData.dispose(pData)` (Disposer thread, `SKSurfaceData.java:358-366`) queues a
bare `DISPOSE_SURFACE` (`SKContext.java:94-101`, no `SET_SCRATCH_SURFACE` before it, unlike
`releaseSkiaSurface`, `:202-219`) into the same buffer as any unflushed draws; `BufferedContext.setSurfaces`
adds no queue reference for the destination, so a VolatileImage that received LCD text and became
unreachable before the next queue flush ends exactly there. A narrow window (GC plus Disposer
between a `drawString` and the frame's flush) but an ordinary one, and a crash rather than a pixel.
*Fix:* `SKBackend_InvalidateState()` should `flushAllBatches()` before it drops the canvas (the
surface is still alive at that point in both opcodes), and/or the hooks should capture the
destination canvas and context when the list begins (install them per list with the current
`g.canvas`, not once). The first is two lines and also removes the dependency for anything added
later.

**F5 -- risk (lifetime, not reachable today): `lcdCoverageCanvas()` replaces the scratch while a
batch may still point at the old canvas.** The assignment at `:1424` releases the previous
`SkSurface`; a pending LCD batch opened on it would draw into a destroyed canvas and its composite
would sample the new, empty scratch. Today every path that can change the destination's size flushes
first (`SKBackend_SetDestination:386`, `SKBackend_GetSurface` re-wrap `:221`, the uncached path
after `flushBatches()`), and within one destination the size is fixed, so the branch only runs with
an empty batch. *Fix:* `SKGlyphCache_Flush(g.cc->glyphCacheLCD)` inside the replacement branch, or
assert the batch is inactive there.

**F6 -- risk (memory and fill): the scratch is sized to the largest LCD destination the context ever
had and lives as long as the context (`SKBackend.h:64`, `:1419-1432`).** A 5K window costs 59 MB
per context; a VolatileImage larger than the screen that once took LCD text pins its size for the
session. The composite is also two fills of the batch's *bounding box* per batch (`:1402-1410`),
so a frame whose glyphs of one colour span the editor pays two editor-sized passes per colour,
independent of glyph count; the VM's grid bench (90 lines) is the favourable layout for this. Not a
defect, but the trade-off should be in the Javadoc/commit before `accumulate` becomes a default.
*Fix options:* a scratch of the batch's bounds (translate the sprites by `-bounds.fLeft/Top`, grow
to a high-water mark but release it in `SKBackend_EndOfFlush` after N idle flushes), or cap and fall
back to `twopass` above the cap.

**Smaller points.**
- kScreen on the opaque RGBA scratch: source alpha 1 (opaque atlas), destination alpha 1, clear is
  `kSrc` black (`:1434-1439`): alpha stays 1, RGB is the union of coverages. Correct, and the union
  of raw coverages then corrected once is closer to the loops than the union of corrected
  coverages, which matches the 15-vs-25 numbers.
- Reachability with a blender or non-SrcOver composite: no; `canRenderLCDText` (`SKSurfaceData.java:241-250`)
  admits only an opaque colour on an opaque destination with SrcOver (alpha 1) or Copy, so `lcdPaint`
  never carries the XOR blender and `g.blend` is ignored by all three modes exactly as Metal's LCD
  shader ignores it.
- `mergeColors` off with an `afterDraw` hook (`SKQuadBatch.h:97`): needed, since kScreen is a blend
  mode and without the guard two colours would merge into a white-keyed mesh and composite white;
  the hooks are installed before the first `begin()` of the list (`:1460-1466` precede `:1659`).
  The hooks stay installed for the process lifetime, which is fine because the mode is global.
- Contrast: `gLcdAccumulateContrast` is updated only after the flush (`:1456-1459`), so a batch is
  composited with its own contrast. Correct (round-4 F1 for this mode).
- **F10 -- nit:** when the scratch cannot be created the LCD glyph is dropped (`:1635-1638`) and
  `lcdPaintReady` stays false, so `lcdCoverageCanvas()` is retried, and an error line logged, for
  every remaining glyph of the list. Fall back to `TwoPass` for the list instead (set `lcdPaint`
  and the passes as that case does) and log once.
- **F7 -- nit:** `SKBackend_DisposeContext` frees the atlases before `recorder.reset()` but
  `lcdTables` and `lcdCoverage` die in `delete cc` after `recorder.reset()` and `context.reset()`
  (`:178-183`). `Recorder.h:264-268` says a surface may be deleted after its recorder; for the table
  images it is the resource cache's shutdown path. Follow the file's own order: clear both before
  resetting the recorder.

## 5. Coverage table (`SKBackend.cpp:1311-1343, 1345-1389`)

- Key `(SkColor << 32) | contrast` per context (`:1320`): colour includes alpha (always 0xff here),
  contrast is the only other input of `lcdCorrectedCoverage`; there is no `linear` dimension in
  Java's LCD blend. Correct.
- Eviction: `tables.clear()` at 64 (`:1326-1328`); shaders already built hold their own `sk_sp` of
  the table (`table->makeShader`, `:1385`), so a pending batch survives the clear. A frame with
  more than 64 colour/contrast pairs rebuilds every table every batch (1 KB upload each): fine for
  an IDE palette, worth a sentence.
- Built on the flusher thread (from `begin()` and `drawLcdPasses`, both under `SKBackend_DrawGlyphList`
  or a flush); `pixels` is a stack array and `uploadPixmap` copies at record time (`:377-383`, the
  comment there holds). Correct.
- Sampling: `table.eval(float2(cov * 255 + 0.5, 0.5))` with nearest filtering and no local matrix
  (`:1356-1358, :1385-1386`): texel `i` is `[i, i+1)`, `cov` is an 8-bit normalised value, in
  `half` on Metal the product `i +- 0.19` worst case, still inside `[i + 0.31, i + 0.69]`: picks
  texel `i`. Correct, including at 0 and 255 (clamp).
- Premultiplication: `kRGBA_8888`, `kOpaque`, alpha 0xff (`:1337-1340`); the shader reads `.r/.g/.b`
  of an opaque image. No premul step anywhere; the values are the corrected coverage quantised to
  8 bits (<= 0.5 level, the `pow` path was float).
- The uncached path uses the same table through `drawLcdPasses` -> `lcdPassShader` (`:1402-1410`).

## 6. Enum plumbing (`SKEnvironment.java:89-118, 166-168, 181, 200`; `SKEnvironment.cpp:35-40`; `SKBackend.h:90-93`)

- `BLEND, TWOPASS, ACCUMULATE` -> `Blend = 0, TwoPass = 1, Accumulate = 2`; `ordinal()` on the Java
  side, `(SKLcdMode) lcdMode` on the native side; both headers say the order is the contract. The
  JNI signature `(IZIZ)V` matches the declaration; the generated header is included, so a mismatch
  would not compile. The verbose line prints `lcd: blend|twopass|accumulate`. Correct.
- **F8 -- nit (Javadoc): the long `Configuration` Javadoc (`:60-88`) now sits above the one-line doc
  of `enum LcdMode` (`:89-90`), so it documents nothing and the record (`:92`) is undocumented.**
  Move the enum above the block or below the record.
- **F9 -- nit: an unknown `sun.java2d.skia.lcd` value selects `BLEND` (`:113-115`), not the platform
  default; on Dawn a typo lands on the slowest path silently.** Fall back to `lcdMode` (the
  default) and say so under `verbose`. Also `java.util.Locale.ROOT` is spelled out twice; the file
  imports otherwise.

## 7. Writing

No non-ASCII characters, em-dashes or arrows in the new text (the pre-existing `RGB -> RGBA` at
`SKGlyphCache.cpp:64` is untouched). Nothing reads as generated prose; the long comments
(`SKQuadBatch.h:210-215`, `SKBackend.cpp:1412-1418`) are dense but in the file's voice. Stale or
awkward spots:

- **F11 -- nit:** `SKQuadBatch.h:86-93` still says an overlapping quad "starts a new draw (push())"
  and `:213` "the layers are drawn in order" (see F3). `SKBackend.cpp:1620-1623` names `lcdTwoPass`
  (gone) and omits `accumulate`. `SKGlyphCache.h:41-43` says "blender or in two passes"; now three
  ways. `SKEnvironment.cpp:27` lists `initNative and gpuApiNative` as platform-side;
  `framebufferFetchNative` is too. `SKQuadBatch.h:95-96` is a parenthetical fragment hanging off the
  previous sentence; fold it in ("... and not with an afterDraw hook, which reads the colour from
  the batch's paint"). The test's `@summary` (`LcdTextBackgroundsTest.java:30`) has one over-long
  line from the edit.

## 8. Other findings in the touched files

- **F12 -- nit (CPU):** `layers.clear()` destroys and `emplace_back()` re-creates every layer per
  draw (68 vectors each); keep the vector and a `layerCount`. `extend()` (`SKQuadBatch.h:338-345`)
  now walks every vertex of every mask-cache batch although that hook ignores the bounds; compute
  them only `if (afterDraw)` or give the mask cache a bounds-free hook. A large XOR rect registers
  itself in every bucket many times over (`cells()` over thousands of cells); cap the cell walk and
  treat such a rect as "all buckets".
- The framebuffer-fetch gate and the layers together change the Dawn blend mode's draw count
  (one copy per layer instead of per glyph); the ledger has the numbers (47 ms stacked, 4.7 grid).
  Nothing to add.
- Not covered by the tests as they stand: the transitive layer case (F3), a layer past 16k quads
  (F4), two LCD colours overlapping in one recording under `accumulate`, LCD text on a disposed
  destination in the same buffer (F1), a contrast change between lists under `accumulate`, a
  clipped destination under `accumulate` (the clip applies to the composite only), high contrast
  (250) in `twopass` (F2), the scratch growing across destinations of different sizes (F5/F6).

## 9. Findings

| ID | Severity | Location | Statement | Fix |
|---|---|---|---|---|
| F1 | bug (accumulate) | `SKBackend.cpp:1434-1445, 1462-1465, 423-426`; `SKRenderQueue.cpp:281-285`; `SKContext.java:94-101` | The accumulate hooks draw on `g.canvas`/`g.cc` at flush time; `DISPOSE_SURFACE` of the current destination nulls `g.canvas` before `ReleaseSurface` flushes the pending LCD batch: null dereference in `drawLcdPasses`. Reachable through the Disposer queuing a bare `DISPOSE_SURFACE` behind unflushed text. | `flushAllBatches()` in `SKBackend_InvalidateState()` before dropping the canvas; or capture canvas and context in the hooks per list. |
| F2 | risk (fidelity) | `SKGlyphCache.cpp:47-49, 82`; `SKBackend.cpp:1311-1317` | Ink threshold 8/255 is on raw coverage; corrected it is 0.084 (contrast 140) to 0.25 (250) for light text, so glyphs sharing a mesh err by up to ~11 levels at 140 and ~32 at 250 where padding meets a neighbour's edge; likely why two-pass reports max 25 against 15. | Threshold the corrected value (raw `(k/255)^gamma`) or use 0 and measure the layer count; verify with the test at threshold 0/1. |
| F3 | risk | `SKQuadBatch.h:284-297, 359-362, 210-215, 90-91` | First-free-layer placement does not keep submission order across transitive overlaps (a quad overlapping only a layer quad lands in the base, drawn first); correct only because XOR, same-colour LCD blend and same-colour two-pass commute. Comments claim ordered draws. | Document the separation invariant, or place a quad in the layer after the highest one it overlaps. |
| F4 | risk (latent) | `SKQuadBatch.h:247, 280-302` | No 16-bit index check under `splitOnOverlap`: layers are unbounded and empty-bounds quads do not count toward 1024. | Check `target->positions.size() + 4 > 0xffff` after choosing the target. |
| F5 | risk (latent) | `SKBackend.cpp:1422-1430` | Replacing the scratch would orphan a pending LCD batch's canvas; every size change flushes first today. | Flush the LCD cache in the replacement branch or assert it inactive. |
| F6 | risk (memory, fill) | `SKBackend.h:64`; `SKBackend.cpp:1419-1432, 1402-1410` | Scratch sized to the largest LCD destination ever, per context, never released; composite is two bounding-box fills per batch. | Bounds-sized scratch with release on idle, or cap and fall back; document before it becomes a default. |
| F7 | nit | `SKBackend.cpp:178-183` | `lcdTables`/`lcdCoverage` are destroyed after `recorder.reset()`/`context.reset()`, unlike the atlases. | Clear both before resetting the recorder. |
| F8 | nit (Javadoc) | `SKEnvironment.java:60-92` | The `Configuration` Javadoc is detached by the inserted `enum LcdMode` and its own doc comment. | Move the enum. |
| F9 | nit | `SKEnvironment.java:110-116` | Unknown property value falls back to `BLEND`, not the platform default (slow path on Dawn, silently); `java.util.Locale` spelled out twice. | Fall back to `lcdMode`, warn under verbose, import `Locale`. |
| F10 | nit | `SKBackend.cpp:1630-1638` | Scratch creation failure drops the glyph, retries and logs per glyph. | Fall back to `TwoPass` for the list, log once. |
| F11 | nit | `SKQuadBatch.h:86-93, 95-96, 213`; `SKBackend.cpp:1620-1623`; `SKGlyphCache.h:41-43`; `SKEnvironment.cpp:27`; `LcdTextBackgroundsTest.java:30` | Stale comments (new draw on overlap, `lcdTwoPass`, two ways not three, platform natives list), a dangling parenthetical, an over-long summary line. | Reword. |
| F12 | nit (CPU) | `SKQuadBatch.h:293, 338-345, 367-368, 236-244` | Layers freed and re-allocated per draw; `extend()` runs for mask batches that ignore bounds; huge XOR rects walk thousands of cells. | Keep layers with a count; bounds only `if (afterDraw)`; cap the cell walk. |

## 10. Assessment

The two commits do what they say: the layering keeps the two-pass batching at one draw pair per
overlap depth, the ink extents make depth 1-2 the norm, the table removes the `pow`s, the gate
restores single-draw blenders on Apple GPUs, and `accumulate` is the cheapest and most faithful
variant on Dawn by the VM's numbers. The Java-to-native plumbing is consistent on both platforms.
F1 is the one thing to fix before `accumulate` is offered beyond the property: a crash on a
narrow but ordinary path that no test can reach because every test flushes before its images go
away. F2 explains the two-pass test's margin and is a one-constant experiment. F3 should at least
be written down, since the layering's correctness rests on it. The rest is housekeeping.
