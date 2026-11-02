# Review of the Skia backend, variant 1 (7 commits, 2026-10-14)

Independent review by a Fable agent of `manuel.unterhofer/JBR-10654-skia-backend` (c4382fc173d5 .. e1d9e1193d9e), read-only, with Metal, Vulkan, Marlin and the shared pipe code as references. My own verification of the top findings is in the ledger §11.14; the report below is the agent's, unedited apart from this header.

---

## Summary

The native core (`SKBackend.mm`, caches, batches) is careful and well-reasoned: the three Graphite rules are encoded in code structure (AsImage dependency, `beforeDraw` upload hook, snap-on-eviction), wire formats match the writers, readback reproduces `div8table`, huge software images are clipped and chunked. The weak spots are all at the **seams with the Metal pipeline**: two render queues, two Disposer records per surface, two lock orders, and a Metal-owned `BufferedContext` for strike disposal. The most important findings:

1. **LCD text is never requested on Skia surfaces** — `SKSurfaceData` lacks the `canRenderLCDText` override, so the whole LCD path from commit 2 is dead at runtime and the ledger's "LCD ≤ 34 levels" measurement most likely compared gray AA against Metal LCD (HIGH, confirmed statically).
2. **Strike disposal runs on the Metal flusher thread** while the Skia flusher reads `GlyphInfo`/cell chains → use-after-free window (HIGH).
3. **Lock-order inversion** (EDT: SK→MTL via texture-paint proxy creation; AppKit: MTL→SK in `drawInMTLContext`) plus a **nested context validation** that can send the current primitive into the proxy texture (HIGH).
4. Texture paints from INT_ARGB/INT_RGB images are read as premultiplied (HIGH); polyline joints are double-drawn (MEDIUM); `SYNC` does not wait for the GPU (MEDIUM).

Quality-wise the code follows the repo's conventions well; there is some stale commentary from earlier commits and avoidable duplication (two quad batches, a copied `QueueFlusher`, a wasted second Graphite context).

---

## Findings (ranked)

### HIGH

**H1. LCD text cannot be enabled on `SKSurfaceData` — CONFIRMED (static)**
`src/java.desktop/macosx/classes/sun/java2d/skia/SKSurfaceData.java` does not override `canRenderLCDText`. The default `SurfaceData.canRenderLCDText` (`SurfaceData.java:504-520`) requires `DrawGlyphListLCD.locate(AnyColor, SrcNoEa, SKSurface)` to find a loop; LCD loops are registered only for software types (`IntRgb.c:126`, `IntArgb.c:82`, …) and `GeneralRenderer` has none, so `haveLCDLoop = LOOP_NOTFOUND` and `SunGraphics2D.java:738-743` downgrades the hint to gray AA. `MTLSurfaceData.java:328-336` overrides exactly for this reason. Consequence: `lcdBlender` (`SKBackend.mm:1077`), the LCD glyph cache, `drawLcdGlyphNoCache`, and the `rowBytesOffset` sub-pixel logic never execute; all LCD comparisons in the ledger need re-measuring.
*Fix:* copy the MTL override (opaque surface, `PAINT_OPAQUECOLOR`, `COMP_ISCOPY` or SrcOver with α ≥ 1). *Evidence to confirm at runtime:* a trace on `ginfo->format` in `SKBackend_DrawGlyphList`.

**H2. Font strike disposal is serialized with the wrong flusher — CONFIRMED (code path)**
`StrikeCache.disposeStrike` (`StrikeCache.java:424-446`) runs `doDispose` via `agc.getContext().getRenderQueue().flushAndInvokeNow`; `MTLGraphicsConfig.getContext()` (`MTLGraphicsConfig.java:168`) still returns the `MTLContext`, so `AccelGlyphCache_RemoveAllCellInfos` + `free(GlyphInfo)` (`sunFont.c:286, 317`) execute on the **Metal** flusher thread. Meanwhile the Skia flusher dereferences `GlyphInfo*` queued in `DRAW_GLYPH_LIST` (`SKBackend.mm:1182-1204`) and walks/mutates `glyph->cellInfo` (`SKGlyphCache.mm:134-148`). On Metal this is safe only because the same `flushAndInvokeNow` drains the pending glyph lists first; the Skia queue is not drained.
*Fix:* when Skia is enabled, make `MTLGraphicsConfig.getContext()` return `SKContext.forConfig(this)` (needs `SKContext` public), so disposal and rendering share the Skia flusher.

**H3. Lock-order inversion and nested validation via texture-paint proxy creation — CONFIRMED path / PLAUSIBLE frequency**
`MTLLayer.java:148-163` (`drawInMTLContext`, AppKit thread via `MTLLayer.m:394-407`) takes MTL lock then SK lock. On the EDT, `BufferedRenderPipe.fillRect` holds the SK lock → `BufferedContext.validate` → `BufferedPaints.setTexturePaint` (`BufferedPaints.java:251`) → `getSourceSurfaceData` → `SurfaceDataProxy.replaceData` (`SurfaceDataProxy.java:436`) → `SKSurfaceDataProxy.validateSurfaceData` → `SKSurfaceData.createData` → `MTLSurfaceData.initSurface` (`MTLSurfaceData.java:267-269`) takes the **MTL** lock. SK→MTL vs MTL→SK = deadlock whenever the display callback coincides. Second effect: `updateSurfaceData` (`SurfaceDataProxy.java:445`) → `SKSwToSurfaceBlit` → `SKContext.validateContext(texture)` runs *inside* the outer `validate()` for the real destination: `SET_SURFACES(texture)` is emitted mid-validation and the outer primitive is then drawn into the proxy texture (BufferedContext does not re-validate after `setPaint`). `MTLPaints.java:114-118` avoids both by pre-caching the proxy *outside* the lock (the "REMIND hack"); `SKPaints.isTextureValid` dropped it.
*Missing evidence:* I did not run it; the mis-targeted primitive should reproduce with a fresh `TexturePaint` on its second use (threshold = 1).
*Fix:* (a) drop `skRq.lock()` from `drawInMTLContext` — submission on `blitCommandQueue` already orders GPU work, the Java lock buys nothing for Skia; (b) restore the MTL validity rule in `SKPaints.isTextureValid` (require `srcData instanceof SKSurfaceData` with `getType() == TEXTURE`, calling `getSourceSurfaceData` twice outside the lock).

**H4. Texture paint reads INT_ARGB/INT_RGB sources as premultiplied — CONFIRMED (static)**
`SKPaints.java:60-63` accepts `TYPE_INT_ARGB`, `TYPE_INT_ARGB_PRE`, `TYPE_INT_RGB`; `SKBackend.mm:690` always passes `PF_INT_ARGB_PRE` to `imageFromSoftwareSurface`. INT_ARGB with α < 255 is mis-decoded; INT_RGB's undefined alpha byte makes the paint transparent or garbage until the proxy texture takes over. Fixed as a side effect of H3(b), or encode the real source type.

**H5. `SKSDOps.mtl` dangles between the two Disposer records — PLAUSIBLE**
`SKSurfaceData` and its `delegate` die together; the Disposer runs `MTLSD_Dispose` and `SKSD_Dispose` in arbitrary order. MTL's path flushes the MTL queue and `SurfaceData_DisposeOps` frees the `BMTLSDOps` (`SurfaceData.c:248-256`), while Skia ops referencing it may still sit in the lazily flushed (≤ 100 ms) SK buffer: `SKBackend_Blit` reads `src->mtl->pTexture` (`SKBackend.mm:1338-1339`), `contextOf()` reads `ops->mtl->privOps` (`:162`). `SKBlitLoops.IsoBlit` adds no reference for the source (same as `MTLBlitLoops.java:264-330`, but Metal is safe because `DISPOSE_SURFACE` is ordered on the same queue). *Missing evidence:* needs GC + Disposer inside the enqueue→flush window; not reproduced.
*Fix:* give `SKSurfaceData` its own `DisposerRecord` that holds the delegate strongly, enqueues the SK `DISPOSE_SURFACE`, then releases the delegate; add `rq.addReference(srcData)` in `IsoBlit`.

### MEDIUM

**M1. Polyline joints drawn twice — CONFIRMED.** `SKBackend_DrawPoly` (`SKBackend.mm:953-958`) emits every segment with both endpoints inclusive. Software passes `shorten` for all but the last open segment (`DrawPolygons.c:83-93`, `LineUtils.h:31-37`); Metal's `LineStrip` shares vertices (`MTLRenderer.m:200-290`). Under XOR the joints cancel to holes; with translucent colour they double-blend. *Fix:* `shorten` parameter in `bresenhamLine`, applied as in `DrawPolygons.c`.

**M2. `SYNC` does not wait for the GPU — CONFIRMED.** `SKRenderQueue.java:48` promises a GPU wait; `SKRenderQueue.mm:286-288` → `SKBackend_EndOfFlush` → `Submit(cc, false)`. Metal's SYNC sets `sync=YES` → `commitCommandBuffer:YES` (`MTLRenderQueue.m:751-755, 119`). `Toolkit.sync()`/Robot tests depend on it. *Fix:* SYNC → `SKBackend_Submit(cc, true)` for every context with `dirty`.

**M3. Cross-context images wrapped with the wrong recorder — CONFIRMED (static), multi-display only.** `SKBackend_GetImage` (`SKBackend.mm:200-213`) uses `contextOf(ops)` (the source's recorder) but the image is drawn on `g.cc`'s canvas (`:1345`, `:673`). Graphite images are bound to their Recorder; Device rejects foreign ones. `SKBackend_FlushRecording(src cc)` (`:1336`) inserts without submitting, and the two `MTLContext`s have distinct queues, so there is no GPU ordering either. *Fix:* wrap with `g.cc->recorder`, `Submit(src cc)` first (shared device; cross-queue needs a CPU sync or MTLSharedEvent).

**M4. A second, unused Graphite context at startup — CONFIRMED.** `SKEnvironment.mm:45-84` creates an `MTLDevice`, queue and Graphite `Context` only to build a description string; `SKEnvironment.java:27` still says "Nothing renders through it yet". Costs Graphite context creation per launch and doubles contexts per device. *Fix:* `isSkiaEnabled` = library loads + default device exists; derive the description lazily from the first `SKConfigContext`.

**M5. Glyph draw order inside/between batches — CONFIRMED (static).** AA and LCD caches flush independently (`SKBackend.mm:790-795`, AA before LCD), so an older LCD batch lands after a newer AA batch; `drawGlyphNoCache`/`drawColorGlyph` (`:1215, 1217, 1241`) draw immediately while earlier cached glyphs of the *same list* are still queued. Visible only for overlapping glyphs under XOR/translucent paint or colour emoji over text; Metal flushes its vertex cache before each NoCache draw. *Fix:* flush the open glyph batch before an immediate draw; one active text batch at a time.

### LOW

**L1.** Bresenham tie-breaking (`SKBackend.mm:889-913`, `e2 > -dy`/`e2 < dx`) was verified against Metal only (§11.13); software's `SETUP_BRESENHAM` (`DrawLine.c:93`) differs from Metal anyway. PLAUSIBLE — one golden test per octant against the software loop would settle it.
**L2.** Stale comments: `SKSurfaceData.java:272-273, 287-290` ("drawn as Src by the native side (no XOR in Skia)" — superseded by commit 5); `SKGlyphCache.h:32` ("drawn with drawAtlas" — now a mesh); `SKBackend.mm:319` ("a cache is a follow-up" — the proxy exists since commit 3).
**L3.** `SKBackend_DisposeContext` (`SKBackend.mm:132-141`) calls `flushAllBatches()` per surface inside the loop and flushes caches again after `Submit` already emptied them — dead work.
**L4.** `SKMetalInterop.h:30-41` mirrors `MTLGraphicsConfigInfo`/`MTLSDOps` by hand although `libawt_lwawt/java2d/metal` is already on the include path (`AwtLibraries.gmk`); include the real headers or add `static_assert(offsetof(...))` checks.
**L5.** Build hygiene: `DISABLED_WARNINGS_clang` includes `unused-function unused-variable` (hides dead code); `_JNI_OnLoad*/_JNI_OnUnload*` exported but not defined; `lib-skia.m4` never checks the `--with-skia` dir against the pin's SHA/marker (stale unpacks accepted); `-std=c++17` relies on "last flag wins". `OPTIMIZATION := LOW` matches `libawt_lwawt`, but the ledger's per-draw CPU figures include `-O1` interpreter code — measure once at HIGH.
**L6.** `unpremul()` (`SKBackend.mm:462-474`) rounds with `(c*255 + a/2)/a` while `unpremulJava` (`:1368`) replicates `div8table`; unify so SET_COLOR round-trips match software.
**L7.** `J2dRlsTraceLn(J2D_TRACE_VERBOSE, …)` on per-destination/per-flush paths (`SKBackend.mm:179, 219, 349, 1533`) evaluates arguments in release builds; Metal uses `J2dTraceLn` for per-op tracing and `Rls` for errors/warnings.
**L8.** `(jint) std::floor(...)` on unbounded doubles in `blitSoftwareSource` (`:1294-1297`) is UB for extreme scales; clamp first.

---

## Consistency with Metal / Vulkan / Marlin / shared pipes

| Area | Skia vs. reference | Verdict |
|---|---|---|
| `validatePipe` (`SKSurfaceData.java:261-336`) | Mirrors `MTLSurfaceData.java:346-430` except no `canRenderLCDText` override (H1) and no `CAPS_PS30` gate (irrelevant) | unjustified (H1) |
| `getMaskFill`, proxy, volatile manager, blit-loop table, `QueueFlusher` | Line-for-line equivalents of MTL | justified; `QueueFlusher` is pure duplication — a shared flusher in `sun.java2d.pipe` would serve MTL/VK/SK |
| Static `SKContext.validateContext` hiding `BufferedContext.validateContext`, native state global | Metal keeps native state per `MTLContext` | justified for the prototype (D5), unjustified long-term; `invalidateContext()` (`BufferedContext.java:443-455`) does reset `xformInUse`, so the scheme is sound |
| Two queues, two Disposer records, `MTLLayer.drawInMTLContext` double lock | Metal: one queue, one record | unjustified — root cause of H2, H3, H5 |
| `IsoBlit` without `addReference` | Same as MTL | justified on Metal, unjustified here (H5) |
| MASK_FILL consumption: `mask + maskoff`, `maskscan`, ≤ 32×32 tiles, null mask → solid rect | Matches `BufferedMaskFill.java:108-121`, `MTLMaskFill.m:60-61` | justified |
| sw→surface blits clipped to the visible rect and chunked by `maxTextureSize`; `SwToSurfaceTransform` for all int types | Metal uploads whole, lacks IntRgb transform (fails `DrawHugeImageTest` semantics) | **should adopt in Metal** |
| Readback via `div8table` replica | Software-exact | justified |
| XOR as runtime blender | Metal commits a command buffer before every XOR draw (`MTLRenderQueue.m:209-213`) | justified (D4); cheaper on CPU than Metal's approach |
| Own glyph atlas (1024², `AccelGlyphCache`) and mask atlas (2048², staged rows) | Metal: `MTLVertexCache` per-tile uploads | justified (D3/D6) |
| Trace levels | `Rls` at VERBOSE on hot paths | unjustified (L7) |
| Naming | `SK*` prefix, ALLCAPS acronyms, camelCase properties all consistent; globals `g`, `gg`, `cc` are terse against the spelled-out rule | mostly justified |
| Vulkan Java side | VK splits window surfaces per toolkit (`WLVK*/X11VK*/Win32VK*`), `VKEnv` naming | SK's single macOS class is fine; `SKEnvironment` spelled out is the documented choice |

---

## Cleanup and optimization opportunities

1. **Restore the MTL texture-paint validity rule** (H3/H4): removes the double lock+whole-image upload per `SET_TEXTURE_PAINT` (`SKBackend.mm:681-697`). Effort: low. Effect: correctness + one fewer upload per paint validation.
2. **Zero-copy uploads:** `imageFromSoftwareSurface` copies into a raster image, then Graphite copies again at record time. `SkImages::RasterFromPixmap` inside the lock is safe because `TextureFromImage` copies at record time; the opaque-type alpha fix could use a reused scratch row. Effort: low. Effect: one memcpy per blitted pixel.
3. **Persistent upload surface per context** for sw blits / mask blits / no-cache glyphs instead of `TextureFromImage` per draw (allocation per distinct size). Pattern already exists in `SKMaskCache`. Effort: medium. Effect: removes texture allocations from `Image*` RenderPerfTests.
4. **`FILL_RECT` into the rect batch** (`SKBackend.mm:864-869`): pixel-identical (integer, identity), turns N rects into one mesh like `FILL_SPANS`. Effort: low.
5. **Batch non-AA parallelograms and blits** with the same paint (each is a `MakeCopy` + `drawVertices` today, `:992-1030, 1258-1274`). Effort: low-medium. Effect: addresses D1's per-draw cost directly.
6. **Merge `RectBatch` and `SKSpriteBatch`** (`SKBackend.mm:807-851` vs `SKSpriteBatch.h`) into one quad batch with optional texcoords. Effort: low.
7. **Shape clip as `SkRegion`/merged spans** instead of one rect per span in a path (`:385-390`); measure Graphite's clip cost on 1000+ spans first. Effort: low.
8. **The single most valuable Graphite experiment:** `drawEdgeAAQuad`/`drawEdgeAAImageSet` with `aaFlags = kNone` for non-AA parallelograms and blits (D1 alt. a) — lets Graphite batch and drop the hand-rolled meshes without changing pixels, if the API stays public.
9. `SKEnvironment` second context (M4), `DisposeContext` redundancy (L3), stale comments (L2): trivial.

---

## Done well — keep or propagate

- The Graphite ordering rules are encoded structurally: `AsImage` for surface→surface dependency (`SKBackend.mm:1338-1343`), `beforeDraw` upload hook (`SKSpriteBatch.h:72-75`, `SKMaskCache.mm:74`), snap-before-eviction (`SKGlyphCache.mm:57-63`, `SKMaskCache.mm:88-97`). Comments explain *why*, not what.
- Readback (`unpremulJava`) and gradient handling (`premulInterpolation`, linear-RGB stops) reproduce software semantics exactly; the two-point conical focal mapping and the 0.25/0.75 gradient axis are right.
- Software-source blits clipped to the visible region and chunked by `maxTextureSize`, plus `SwToSurfaceTransform` for all int types — Metal should adopt both.
- Submitting on `blitCommandQueue` to order presentation without a CPU wait is a better design than Metal's wait for window destinations.
- Build integration: pinned tag + per-platform SHA-256, `fetch_skia.sh` idempotent with marker, hidden exports list, `-DSK_RELEASE` with the layout-mismatch rationale.
- Mask-cache row staging and sprite meshes instead of `drawAtlas` are measured, documented decisions.
