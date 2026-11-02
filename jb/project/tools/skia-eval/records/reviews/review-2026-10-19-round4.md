# Review round 4 of `manuel.unterhofer/JBR-10654-skia-backend` (HEAD 098ad934afa5)

Date: 2026-10-19. Scope: the two commits cherry-picked from the Windows VM, `06e94c106071` (one Dawn
device per adapter, `DawnSKPlatform.cpp`) and `098ad934afa5` (LCD text in two passes,
`SKEnvironment.java/.cpp`, `SKBackend.{h,cpp}`, `SKGlyphCache.{h,cpp}`, `SKQuadBatch.h`), read with
`git show` and then as whole functions at HEAD together with their callers (`SKMaskCache.cpp`,
`SKSurfaceData.java`, `Win32SKScreenUpdateManager.java`, `Win32SKGraphicsConfig.*`,
`MTLSKPlatform.mm`, `SKBackend_Blit`). The eight changed files are byte-identical in the jbr25
worktree (`build/wt-jbr25`, e7ba02962f56). Builds, the render matrix and the LCD probe were
verified by the author and are not repeated here. Severity as in round 3: **bug** (wrong today under
a reachable condition), **risk** (latent, or correct on an unstated assumption), **nit**.

---

## 1. Two-pass LCD (`098ad934afa5`)

### 1a. Batching key

The glyph batch is keyed by `(canvas, atlas image, twoPass pointer, paint)` (`SKQuadBatch.h:95-103`).
With two-pass the paint is `lcdPaint` with `setColor(g.color)` and the default SrcOver blend mode
(`SKBackend.cpp:1493-1495`), `mergeColors` is false because `passes != nullptr` (`:90-92`), so
`same = paint == p` compares the colour: **two colours never share a mesh**, two canvases never
share one (`:95`). The shaders are built in `begin()` after `flush()` (`:115-134`), from the paint
passed in and from `gLcdContrast` as it is *at that moment*; the previous batch is drawn by that
`flush()` with its own stored `meshPaint`/`secondPaint` (`:214-237`), which were built at *its*
`begin()`. So the ordering `gLcdContrast = lcdContrast` (`:1495`) before `SKGlyphCache_Begin`
(`:1511`) is right and the previous batch's shader is never rebuilt with the new contrast.

**F1 -- bug (low): the contrast is not part of the key.** Two consecutive glyph lists with the same
colour and canvas but different `lcdContrast` take `same == true` (`SKQuadBatch.h:102`) and the
second list's sprites are drawn with the first list's shaders, i.e. the first list's gamma.
Reachable: `lcdContrast` is `SunGraphics2D.lcdTextContrast` per glyph list
(`BufferedTextPipe.java:146`), set by `RenderingHints.KEY_TEXT_LCD_CONTRAST`
(`SunGraphics2D.java:1223`); two components with different contrast hints painted in sequence in the
same colour. The blender path does not have this problem because `lcdBlender(contrast)` returns a
different `SkBlender` object for a different contrast and `SkPaint::operator==` compares the blender
pointer. *Fix:* in `SKBackend_DrawGlyphList`, before `SKGlyphCache_Begin` for the LCD cache, when
`gConfig.lcdTwoPass && lcdContrast != gLcdContrast` call `SKGlyphCache_Flush(g.cc->glyphCacheLCD)`
(then set `gLcdContrast`), or give `SKQuadBatch::begin` an integer key argument that two-pass passes
the contrast through.

### 1b. Uncached path

`drawLcdTwoPassImage` (`SKBackend.cpp:1352-1363`) matches the cached path: clamp tiling, nearest
sampling, image shader translated to `(x, y)` with the rect at `(x, y, w, h)`, the same two shader
builders and blend modes read from `gLcdTwoPass`, the same paint (`lcdPaint`) copied, the same
`gLcdContrast`. `fx, fy` are integer (floored, `:1452`), so the non-AA `drawRect` is pixel-aligned
and Graphite adds no coverage edges. `flushBatches()` runs before it (`:1517`), so order against the
batched glyphs is kept. Consistent.

### 1c. `splitOnOverlap` and the two passes

`splitOnOverlap = !p.asBlendMode().has_value()` (`SKQuadBatch.h:89`) is false for two-pass (SrcOver
on the key paint). Pass 1 is fixed-function kModulate and pass 2 kPlus, both read the real
framebuffer, so *within* a pass overlapping quads of one mesh compose sequentially and correctly.
The two passes do not, however, interleave per glyph:

**F2 -- bug (fidelity, low): overlapping glyphs in one two-pass mesh over-add the second pass.**
For two overlapping quads with corrected coverages `w1, w2` and text channel `s`, the mesh gives
`Cd(1-w1)(1-w2) + s*w1 + s*w2`; drawing them in sequence (what the blender path produces, and what
the software loops produce) gives `Cd(1-w1)(1-w2) + s*w1(1-w2) + s*w2`. The excess is `s*w1*w2`,
clamped by kPlus: zero for black text (the probe's case, hence "max 2"), up to 0.25 (64 levels) for
white text where two 50 % edges meet, and for the same light string drawn twice at the same position
(faux bold) the interior doubles (`s = 0.5`: 0.5 -> 1.0). Reachable with italics, kerned pairs and
ligatures in light text on dark themes. A full per-overlap split would bring back one draw pair per
glyph (the LCD images carry the filter's padding, so adjacent glyphs' bounds overlap by
construction) -- two draws each without a destination copy, ~2 us CPU apiece, i.e. tens of
milliseconds for the 500-line bench. *Fix that keeps the batching:* under two-pass set
`splitOnOverlap` and test overlap on the bounds inset by the padding (1 px); the padding pixels
carry only the filter's spill, so the remaining error is `s * (spill) * w` -- a few levels -- and the
split happens only where ink overlaps ink. **To verify** with `build/skia-smoke/LcdOverlap.java` in
white on black before and after.

### 1d. Destination alpha and translucent colours

The pass shaders output alpha 1 (`SKBackend.cpp:1314-1315`): kModulate keeps `dst.a`, kPlus sets
`dst.a = min(1, dst.a + 1) = 1` over the *whole* quad including zero-coverage pixels; and a paint
colour with alpha < 1 would scale both shader outputs, darkening the whole glyph box by that alpha in
pass 1. Neither is reachable today: `SKSurfaceData.canRenderLCDText` (`SKSurfaceData.java:241-250`)
requires an opaque destination (`getTransparency() == OPAQUE`), `PAINT_OPAQUECOLOR` and
SrcOver with extra alpha 1 (or `COMP_ISCOPY`), exactly as `MTLSurfaceData.java:328-334`; LCD with a
translucent colour, an `AlphaComposite` alpha, XOR or a translucent VolatileImage is downgraded to AA
by `SunGraphics2D`. On an opaque premultiplied destination the two passes are a convex combination
per channel (`<= 1`) and alpha stays 1.

**F5 -- risk: the native side relies on that gating without saying so.** The blender path degrades
gracefully (it ignores the alpha and touches only covered pixels); the two-pass path would draw
visible boxes (dark boxes for a translucent colour, opaque black boxes on a transparent image). A
one-line comment at `SKBackend.cpp:1282` ("opaque colour on an opaque destination, as
`SKSurfaceData.canRenderLCDText` guarantees") or `SkASSERT(SkColorGetA(g.color) == 0xff)` at `:1494`
would pin the assumption.

### 1e. Effects, options, thread safety

`effects[2]` are compiled once and leaked on purpose (`release()`, `:1320`), as `lcdBlender`'s
effect is; `MakeForShader(SkString)` is the overload that passes `Options{}`
(`SkRuntimeEffect.h:177-179`), nothing to cache. All of it (`effects`, `gLcdContrast`, `gLcdTwoPass`,
the batch) is touched from `SKBackend_DrawGlyphList` only, i.e. on the flusher thread like every
other static in the file (round 3, "Clean"). Uniform layout: `half3 src, half3 srcGamma, half invGamma`
are stored as floats by `SkRuntimeEffect` (7 x 4 = 28 bytes) and `LcdTwoPassUniforms` is 7 floats;
`makeShader` returns null on a size mismatch, so a wrong edit would show as no text rather than a
crash (an `SkASSERT(effects[i]->uniformSize() == sizeof(LcdTwoPassUniforms))` would say why, F13).

- **F6 -- nit (robustness):** if `effects[0]` compiles and `effects[1]` does not (`:1313-1321`),
  `effects[0]` stays set, the function returns null once, and every later call skips the compile
  block and dereferences `effects[1] == nullptr` (`:1338`). Unreachable in practice (the two sources
  differ only in `main`), but the guard should be `effects[0] == nullptr || effects[1] == nullptr`,
  or compile into locals and assign both at the end.

### 1f. The SkSL

- Denominator `mix(1 - src, src, src) = (1-s)^2 + s^2`, minimum 0.5 at `s = 0.5`: **no division by
  zero** for any channel value, including exactly 0 and 1.
- `pow(mix(1, srcGamma, cov), invGamma)`: base in `[srcGamma, 1] ⊂ [0, 1]`, exponent
  `1/(contrast/100) > 0`; `pow(srcGamma * cov, invGamma)`: base in `[0, 1]`. `pow(0, y)` with `y > 0`
  is defined as 0 in GLSL, HLSL and MSL (`exp2(y * log2(0)) = exp2(-inf) = 0`); the blender's early
  return on zero coverage is an optimisation, not a guard. With `cov = 0`: `onWhite = 1 - 1 = 0`,
  `onBlack = 0`, coverage 0, pass 1 multiplies by 1, pass 2 adds 0: the glyph box outside the ink is
  untouched. No NaN can arise from these inputs, so `saturate` is a clamp of the convex combination
  (the result is a `(1-s)^2 : s^2` weighted mean of the two exact weights, hence already in `[0, 1]`),
  not a NaN hider.
- The derivation checks out: on white the exact linear weight is `(1 - Cr) / (1 - s)`, on black
  `Cr / s`; the code forms `((1-s)*onWhite + s*onBlack) / ((1-s)^2 + s^2)`, which equals
  `((1-s)^2 * wWhite + s^2 * wBlack) / ((1-s)^2 + s^2)`: exact for `s = 0` on white and `s = 1` on
  black, a weighted mean elsewhere, as the comment claims.
- **F12 -- to verify: `half` precision on Metal.** Graphite emits SkSL `half` as 32-bit `f32` in
  WGSL (no f16 feature requested), so the VM's "within 2 levels" measured float arithmetic; on Metal
  `half` is 16-bit (11-bit mantissa, relative 5e-4, two `pow` per channel per pass). Expected well
  under one level per operation, but it has not been measured: run `build/skia-smoke/LcdBackgrounds.java`
  on the Mac with `-Dsun.java2d.skia.lcd=twopass` (the handover asked for this) and compare with the
  ledger's table. If it is off, change the four `half` declarations to `float`.

### 1g. Rest of the tree

- Other `begin()` callers: `SKMaskCache.cpp:138, 156` (default `passes = nullptr`, unchanged
  semantics); `gRects.begin` x 9 in `SKBackend.cpp` (no atlas). The AA glyph cache passes no
  `passes` (`SKBackend.cpp:1474`). `twoPass == passes` in the key means the mask and AA batches never
  pick up `secondPaint`.
- **F7 -- nit:** `twoPass = passes` is set unconditionally (`SKQuadBatch.h:124`) while the pass
  shaders are only built `if (atlas)` (`:126-134`); a `begin()` with passes and no atlas would draw a
  stale `secondPaint` (`:235-237`). Unreachable (only `SKGlyphCache_Begin` passes it, always with
  its atlas); move the assignment under `if (atlas)` or `SkASSERT(passes == nullptr || atlas)`.
- **F10 -- nit:** `SKGlyphCache.h:41-43` still says LCD caches "are drawn through the paint's
  blender"; now "through the paint's blender or in two passes (`SKQuadBatchTwoPass`)".
- `SKQuadBatch.h:83-88` ("A runtime blender (XOR, LCD) reads the destination") stays correct: it
  describes the blend-mode LCD; fine.

## 2. `SKEnvironment`

- `Configuration(int, boolean, boolean)` record, `defaultsFor(gpuApi)` now uses its parameter
  (`SKEnvironment.java:94-95`, closes the round-3 nit); `withProperties` treats any value other than
  `twopass` (case-insensitive) as `blend`, same convention as `blitQuads` (`:101-103`).
- Javadoc (`:72-80`): states both values, where each is exact and cheap; "fourth knob" (`:83`) is
  right (msaa, blitQuads, lcd, shapes). The verbose line prints `lcd: twopass|blend` (`:167`).
- JNI: `configureNative(int, boolean, boolean)` (`:183`) against
  `Java_sun_java2d_skia_SKEnvironment_configureNative(JNIEnv*, jclass, jint, jboolean, jboolean)`
  (`SKEnvironment.cpp:36-37`); the generated header is included (`:30`), so a mismatch would not
  compile, and both lines build. `edgeAAQuads == JNI_TRUE`, `lcdTwoPass == JNI_TRUE` to `bool`
  (`:39`): correct.
- **F4 -- risk (performance, macOS on Intel/AMD GPUs): `framebufferFetch = "metal".equals(gpuApi)`
  (`:94`) is a statement about the API, not the GPU.** Graphite's Metal backend reads the destination
  through framebuffer fetch on Apple-family GPUs only; on Intel and AMD Macs it falls back to the
  texture-copy strategy, the very case the two-pass default exists for (the Javadoc itself says
  "Metal on Apple GPUs", `:76`). LCD text is not the macOS default, but it is one hint away
  (`-Dawt.useSystemAAFontSettings=lcd`, or an application hint) and on such a Mac it lands on the
  one-pass-per-glyph path. *Fix:* decide it natively: `MTLSKEnvironment.mm` can answer
  `[device supportsFamily:MTLGPUFamilyApple1]` (a `framebufferFetchNative()` next to
  `gpuApiNative()`, or `gpuApiNative` returning `metal-apple`/`metal`), and `defaultsFor` takes that.
  **To verify** that Graphite m156 `MtlCaps` indeed keys `fDstReadStrategy`/framebuffer-fetch support
  on the Apple family (the prebuilt ships headers only; the ledger's reading of `Caps::getDstReadStrategy`
  as "framebuffer fetch if `fFBFetchSupport` else texture copy" supports it).

## 3. Shared Dawn device (`06e94c106071`)

**Lifetime.** `gDevices[i]` (`DawnSKPlatform.cpp:83`) holds one `shared_ptr<DawnSKDevice>` per
backend for the process lifetime; each `DawnSKConfigInfo` (`:228-229`), each `DawnSKSurface`
(`:333`) and, through `SKPlatform_MakeContext` (`:415-416`), each Graphite context hold their own
reference. `DawnSK_DisposeConfig` (`:239-241`) deletes the info and drops one reference; the device
survives disposal of every configuration, which is the point (the next `getDefaultConfiguration()`
after a display change reuses it, "shared device" trace `:231-232`). The round-3 leak via `recovering`
is gone (`Win32SKScreenUpdateManager.java:69`, weak set), so a *lost* device is released when the
last disposed configuration/surface/context on it goes.

**Device loss.** `gpu->lost->load()` is checked under the lock (`:207-210`) and the cache entry is
dropped before a new adapter/device is requested; the lost `DawnSKDevice` stays alive for its
remaining owners only. A loss that happens between the check and the first use is caught by
`DawnSK_IsLost` in `DawnSK_CreateSurface` (`:322`) / `SKPlatform_MakeContext` (`:410`), which the
Java side turns into `recover`, bounded by `MAX_RECOVERY_ATTEMPTS = 3`
(`Win32SKScreenUpdateManager.java:72-73, 164-166`). Both callbacks are descriptor-set
(`requestDevice:171-184`), so a second Graphite context on the same device cannot replace them.
Correct.

**Adapter/backend selection.** The array is indexed by *backend type* (`kBackends`, `:73-74`), and
the loop returns at the first backend that yields a device (`:233`), so there is one device per
process in practice (D3D11 on every machine that has it; the adapter is Dawn's `HighPerformance`
pick for that backend, `:110`). "Per adapter" (title, `:76`) is true only in the sense that the
adapter for a backend does not change while the process runs; a second adapter (a second GPU with
its own display) never gets a device, which is the same limitation as before the commit
(`Win32SKGraphicsConfig.cpp:40-41` says so). **F8 -- nit:** say "one device (per backend)" in the
comment and the title; and the commit message's first paragraph lists "a Graphite context and new
glyph and mask atlases" among the costs removed, but `SKBackend_ContextFor` still creates one
`SKConfigContext` (context, recorder, both glyph caches, mask cache) per configuration
(`SKBackend.cpp:122-147`): only the device is shared. Fine as a design (per-configuration contexts
keep the cross-context protocol unchanged), but the message overstates it.

**Thread safety.** `gDevicesLock` (`:82, :203`) covers the array; `requestAdapter`/`requestDevice`
block under it with `WaitAny` (synchronous by `TimedWaitAny`), so two threads cannot create two
devices for one backend. `DawnSK_CreateConfig` is called from `getConfigInfo` on whichever thread
calls `getDefaultConfiguration()` (EDT normally); nothing else touches `gDevices`.

**`SKPlatform_SameDevice` (`:427-432`).** Now true across every Dawn configuration, which makes
the *other-context, same-device* branch of `SKBackend_Blit` (`SKBackend.cpp:760-764`: synchronous
`SKBackend_Submit` of the source's context, then `SKBackend_GetImage` wraps the texture in the
destination's recorder) reachable on Windows for the first time (old VolatileImage, new window,
during a display change). Both contexts submit to the same `wgpu::Queue`
(`device.GetQueue()`, `:223`), so the sync-to-CPU submit plus queue order make the source complete
before the destination samples it; the commit message reports the probe (`DisplayChangeProbe`)
drawing from configuration 0 into configuration 2. The promise is now exactly what Metal makes:
`MTLSKPlatform.mm:78-89` compares `[mtlc device]`, which is the same `id<MTLDevice>` for every
`MTLContext` on the same GPU, so configurations of different displays on one GPU share textures on
both platforms, and only genuinely different GPUs are skipped. Not over-promised.

- **F9 -- nit (process exit):** `gDevices` is a namespace-scope static with a non-trivial destructor,
  so if every configuration was disposed before exit (unusual: the Disposer rarely gets to them),
  the last `wgpu::Device` is destroyed during `DLL_PROCESS_DETACH` next to Dawn's own statics. The
  existing `static wgpu::Instance` (`:90`) has the same shape, and `DawnSKConfigInfo`s normally keep
  the device alive past that point, so this is not new in kind; the JDK's habit is to leak such
  objects (`static auto *gDevices = new std::shared_ptr<DawnSKDevice>[kBackendCount]`, or simply
  `release()` in a `std::atexit`-free way).
- `DawnSK_DescribeAdapter` (`:144-149`) still requests a fresh adapter for the `initNative` string
  rather than describing the cached device; called once, harmless (carried, unchanged).

## 4. Writing

No non-ASCII characters in either commit; no "note that", em-dashes, arrows, or lists in comments;
the identifiers (`lcdTwoPass`, `SKQuadBatchTwoPass`, `gLcdContrast`, `drawLcdTwoPassImage`,
`kBackendCount`, `gDevicesLock`) follow the file's conventions. Two small things:

- **F11 -- nit:** `SKBackend.cpp:104-105` has two consecutive blank lines after
  `SKBackend_Configure` (the commit added one).
- The DirectWrite/dual-source sentence appears three times (`SKBackend.cpp:1282-1286`,
  `SKQuadBatch.h:50-53`, `SKEnvironment.java:76-78`) plus the commit message; `SKQuadBatch.h` should
  describe the mechanism only ("two draws of every mesh with their own blend modes and shaders") and
  leave the why to `SKBackend.cpp`.

## 5. Other findings while reading (outside the two commits)

**F3 -- bug (pre-existing since the batching commit, not introduced here; reachable whenever the
glyph atlas is full): an eviction in the middle of a glyph list drops the rest of the list.**
`AccelGlyphCache_AddGlyph` calls the cache's flush callback right before it overwrites an occupied
cell (`AccelGlyphCache.c:224-229`); `flushForEviction` (`SKGlyphCache.cpp:66-72`) calls
`gAdding->batch.flush()`, which draws the pending mesh and then **closes the batch**
(`SKQuadBatch.h:178-182`: `canvas = nullptr`, `atlasImage.reset()`). `SKGlyphCache_Draw` then
uploads the glyph and calls `gc->batch.addSprite` (`SKGlyphCache.cpp:155-158`) on the closed batch:
`push()` appends with no check of `active()` (`:185-212`), `drawPending()` draws only
`if (canvas != nullptr ...)` (`:215`) and otherwise *clears* the vectors. `SKBackend_DrawGlyphList`
calls `SKGlyphCache_Begin` once per list (`SKBackend.cpp:1473-1475, 1510-1512`, guarded by the
local `aa`/`lcd`), so nothing re-opens the batch until the next glyph list: the glyph that caused
the eviction and every later cached glyph of that list are discarded at the next `begin()`/`flush()`.
The mask cache does not have this problem: after its wrap-around flush (`SKMaskCache.cpp:112-116`)
it calls `begin()` again (`:138`). The atlas has 1024 cells (`SKGlyphCache.cpp:43-44`), one per
glyph *sub-image* (`:144, :154`; sub-pixel strikes render 4 or 16 variants), so an editor view
with a few fonts fills it and evictions are routine in a long session; the probes
(`LcdOverlap`, `LcdTextBench`: one 84-glyph line) never fill it. *Fix:* give `SKQuadBatch` a public
`draw()` (= `drawPending()`, keeps canvas/paint/atlas) and call that from `flushForEviction`
instead of `flush()`; the recording still ends through `SKBackend_FlushRecording` (which calls
`SKGlyphCache_Flush`; make that call `draw()` too, or let `FlushRecording` close the batches and
have `SKGlyphCache_Draw` re-`begin()` when `!batch.active()` with the canvas and paint stored at
`SKGlyphCache_Begin`). **To verify** with a probe that draws > 1024 distinct glyph images in one
`drawString` (several fonts and sizes, or sub-pixel positioning) and counts ink, as `MissingInk.java`
does.

Nothing else new since round 3 in the files read.

## 6. Findings

| ID | Severity | Location | Statement | Fix |
|---|---|---|---|---|
| F1 | bug (low) | `SKBackend.cpp:1341, 1495, 1511`; `SKQuadBatch.h:95-103` | Two-pass batch key omits `lcdContrast`; a same-colour list with another `KEY_TEXT_LCD_CONTRAST` is drawn with the previous list's gamma. | Flush the LCD cache when `lcdContrast != gLcdContrast` before `SKGlyphCache_Begin`, or pass the contrast into the batch key. |
| F2 | bug (fidelity, low) | `SKQuadBatch.h:89, 235-237` | Overlapping glyphs in one two-pass mesh add `s*w1*w2` too much (zero for black text, up to 64 levels for light text at crossing edges, interior doubling for a light string drawn twice). | Under two-pass set `splitOnOverlap` with bounds inset by the 1-px padding; verify with `LcdOverlap` white on black. |
| F3 | bug (pre-existing) | `SKGlyphCache.cpp:66-72, 148-158`; `SKQuadBatch.h:178-182, 215` | Eviction flush closes the batch mid-list; the evicting glyph and the rest of the list are appended to a closed batch and discarded. | `flushForEviction` draws without closing (`SKQuadBatch::draw()`), or `SKGlyphCache_Draw` re-begins when `!active()`. Verify with a > 1024-glyph probe. |
| F4 | risk (performance) | `SKEnvironment.java:94-95` | `framebufferFetch = "metal".equals(gpuApi)` is false on Intel/AMD Macs, where Metal uses the texture-copy read and LCD text takes the per-glyph pass path. | Decide natively (`supportsFamily:MTLGPUFamilyApple1`) and feed `defaultsFor`. To verify against m156 `MtlCaps`. |
| F5 | risk | `SKBackend.cpp:1282, 1314-1315, 1494`; `SKSurfaceData.java:241-250` | Two-pass alpha semantics (alpha 1 over the whole quad, paint alpha scaling pass 1) are only safe because Java gates LCD to opaque colour on opaque destination; unstated in the native code. | Comment the assumption at `:1282` or `SkASSERT(SkColorGetA(g.color) == 0xff)`. |
| F6 | nit | `SKBackend.cpp:1298-1322, 1338` | Partial compile leaves `effects[1] == nullptr` with the compile block skipped on later calls: null dereference. | Guard both slots, or assign both after both compiled. |
| F7 | nit | `SKQuadBatch.h:124, 126-138, 235` | `twoPass` is set without an atlas; a stale `secondPaint` would be drawn (unreachable). | Set under `if (atlas)` or assert. |
| F8 | nit | `DawnSKPlatform.cpp:76-83`, commit title/message | "Per adapter" is per backend (one device per process in practice); the message claims the Graphite context and atlases are shared, but `SKConfigContext` is still per configuration. | Reword comment and message. |
| F9 | nit | `DawnSKPlatform.cpp:83` | Static `shared_ptr` array destroyed at DLL unload could tear down a `wgpu::Device` during process exit if all configurations were disposed; same shape as the existing static instance. | Leak intentionally, JDK style. |
| F10 | nit | `SKGlyphCache.h:41-43` | Comment says LCD caches are drawn through the paint's blender; now also two passes. | Update the sentence. |
| F11 | nit | `SKBackend.cpp:104-105` | Two consecutive blank lines. | Remove one. |
| F12 | to verify | `SKBackend.cpp:1300-1312` | `half` is 16-bit on Metal (f32 on WGSL where it was measured); two-pass fidelity on Metal unmeasured. | Run `LcdBackgrounds` with `-Dsun.java2d.skia.lcd=twopass` on the Mac; switch to `float` if off. |
| F13 | nit | `SKBackend.cpp:1293-1295, 1338` | Uniform block size checked by hand (28 bytes) but not asserted. | `SkASSERT(effects[i]->uniformSize() == sizeof(LcdTwoPassUniforms))`. |

## 7. Assessment

The two commits do what they say and are consistent with the rest of the tree: the JNI signature,
the `begin()` callers, the Metal half (`SameDevice` semantics now match Metal's), the Javadoc and the
jbr25 copies all agree. F1 and F2 are small, reachable fidelity defects of the new default on
Windows and should be fixed before the performance run, F4 before any macOS LCD measurement, F12 by
running the existing probe once on the Mac. F3 is older than both commits and the only item here
that loses content under ordinary use; it belongs in the same fix round.
