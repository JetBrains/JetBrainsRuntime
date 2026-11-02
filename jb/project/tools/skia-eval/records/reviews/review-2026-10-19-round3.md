# Review round 3 of `manuel.unterhofer/JBR-10654-skia-backend` (HEAD e771f9cbdd05 vs `main` 44bb04f436c9)

Date: 2026-10-19. Scope: the resulting tree of 17 topical + 3 `fixup!` commits (63 files,
+8230/-35), read against `build/notes/review-2026-10-16-round2.md`. Every statement below was
checked against the sources at HEAD; what could not be settled from the tree is marked
**to verify** with what would settle it. Severity: **bug** (wrong today under a reachable
condition), **risk** (latent, or correct on an unstated assumption), **nit** (quality).

## 0. Status of round 2

All round-2 items are addressed in the tree; none of the fixes is wrong, two are incomplete in a
way that matters only under device loss or for Windows text throughput (sections 1a and 1c).

| Item | State at HEAD |
|---|---|
| A1 `g.dst` nulled inside `SetDestination` | fixed: surface taken first, `g.dst` assigned after (`SKBackend.cpp:381-389`) |
| A2 dead `tryLock`/`nativeStartRedraw` | fixed: gone from `MTLLayer.java` and `MTLLayer.m`; one-line comment at `MTLLayer.java:147-150` |
| A3 no recovery after device loss | implemented via `recover(gc)` (section 1c: works for windows; incomplete for VolatileImages, unbounded on repeated loss) |
| A4 LCD glyphs one draw per list | fixed by `drawPerQuad` (section 1a: correct; costs a draw per glyph on Metal and a pass per glyph on Dawn) |
| A5 Linux configures and fails to link | fixed: `lib-skia.m4:129-131` errors for every OS but macOS/Windows |
| A6 cross-device sources | fixed by `SKPlatform_SameDevice` (section 1b) |
| A7 `SET_SCRATCH_SURFACE` creates a context | fixed: lookup without creation (`SKBackend.cpp:402-410`) |
| A8 GDI surface per `getGraphics()` | fixed: per-surface map (section 1c) |
| B1 off-screen surfaces never lost | fixed: `Win32SKSurfaceData.isSurfaceLost()` (`:138-141`) |
| nits: `forgetSurface(dispose)`, `SKBackend_CurrentCanvas`, probe comment, `backendType`, non-ASCII, `AC_SUBST` | all gone; non-ASCII scan of all 63 files: none |
| nits still open (unchanged, trivial) | `lib-skia.m4:73` first `out/*/` wins; `SKEnvironment.java:77` `defaultsFor(gpuApi)` ignores its parameter; `MTLTextRenderer.m:131` `MTLTR_CACHE_HEIGHT` referenced only by a comment |

---

## 1. The three fix-ups

### 1a. `d36f542a219f` SKQuadBatch: one draw per quad under a runtime blender

**Correctness: clean.** `begin()` sets `drawPerQuad` from the incoming paint before the `same`
check (`SKQuadBatch.h:69`); the `drawPending()` at `:78` draws whatever is pending with the
*previous* `meshPaint` (key and mesh paint are only replaced after `flush()` at `:104-113`), so a
blend-mode batch followed by an XOR/LCD paint, and the reverse, both draw with the right paint.
`push()` drains before adding (`:165-167`), so no two quads share a draw and the last quad stays
pending until the next `begin()`/`push()`/`flush()`, exactly as before. Interaction with the two
caches checked: the mask cache extends the dirty staging span *before* `begin()`
(`SKMaskCache.cpp:136-139`), so the `drawPending()` inside `begin()` uploads the span covering
both the pending tile and the new one, and the new tile's draw is recorded after the upload; the
glyph cache records `writePixels` of glyph N (`SKGlyphCache.cpp:155`) before `push()` issues the
draw of glyph N-1, and Graphite orders a texture's uploads ahead of draws recorded later. Colour
merging cannot coexist with `drawPerQuad` (`mergeColors` requires a blend mode, `:80`), so
`colorPerVertex` is never set in that mode.

**`SKQuadBatch.h:69-79`, `:165` -- risk (performance), to verify by measurement: the fix is
unconditional, and on Windows it changes the default text path.** LCD text is the default Swing
text mode on Windows (ClearType desktop hints), so every glyph of every string is now its own
`drawVertices` with a runtime blender. On Metal (Apple GPUs, framebuffer fetch) that is one draw
(about 2 us CPU by the header's own figure, plus an `SkVertices::MakeCopy`) per glyph where one
per list was already correct; on Dawn/D3D11 a dst-copy draw additionally makes Graphite flush the
pending work of the device before the draw (`Device::needsFlushBeforeDraw` returns true for
`DstReadStrategy::kTextureCopy`; **to verify** in m156 `src/gpu/graphite/Device.cpp`, which the
prebuilt under `build/deps/skia` does not ship), i.e. one draw pass and one destination copy per
glyph. A text-heavy frame on the Windows VM will show this directly. *Fix:* split only on
overlap, which keeps the exact output of the fix and most of the old batching: while
`drawPerQuad`, keep a small `std::vector<SkRect>` of the device-space bounds of the quads in the
open batch; in `push()` call `drawPending()` only if the new quad's bounds intersect one of them
(a text line then stays one draw except at kerned pairs and italics; two XOR strokes split at
their crossing). Rename the flag `splitOnOverlap`. Measure before/after on the Windows VM with a
`drawString` loop under `-Dawt.useSystemAAFontSettings=lcd` and on macOS with the same plus
`-Dsun.java2d.skia.verbose` to confirm the glyph counts; record the result in the PR.

- `SKQuadBatch.h:78` -- nit: with the check in `push()`, the `drawPending()` in `begin()` is
  redundant (when `same` is false `flush()` draws, when `same` is true the next `push()` drains).
  Harmless; drop it or keep it with a one-word reason ("eagerly").

### 1b. `853c3ad7f025` `SKPlatform_SameDevice` and the skip in `SKBackend_Blit`

**Reachable on macOS: yes.** `MTLContext.m:365` creates each display's device with
`CGDirectDisplayCopyCurrentMetalDevice(displayID)`, so on a Mac Pro, with an eGPU, or on a
dual-GPU Intel MacBook whose built-in panel sits on the integrated GPU, two `MTLGraphicsConfig`s
hold different `MTLDevice`s, and a VolatileImage created on one display blitted into a window on
the other (a Swing back buffer during a drag between displays, until `RepaintManager` recreates it
for the new configuration) takes the skip. Apple-silicon Macs with one GPU never take it. The
Metal pipeline itself refuses every cross-*context* isoblit silently (`MTLBlitLoops.m:398-401`,
`RETURN_IF_TRUE` on a context mismatch), so the Skia backend is already more permissive than the
pipeline it wraps.

**Warning level: right.** `J2dRlsTraceLn(J2D_TRACE_WARNING, ...)` is compiled in but gated by
`J2D_TRACE_LEVEL` (`Trace.c:44`, default off), so it is silent in production and visible under
tracing, where one line per skipped blit is what a developer wants; it matches the other skips in
the file (`:670`, `:746`). The Metal pipeline says nothing at all.

**State after the skip: consistent.** `REQUIRE_CANVAS()` has already run (`:1560`): batches are
flushed in order and the window is in `touchedWindows`, so the frame is presented with the rest
of its content; nothing is half-recorded, no source submit happened.

**Dawn side: correct and currently the only path that matters.** On Windows only the default
device has a Skia configuration, so two live configurations exist only across a display change
(old and new, each with its own `wgpu::Device`, `DawnSK_CreateConfig:197-198`): `SameDevice` is
false, the old images draw nothing on the new window until the `IMAGE_INCOMPATIBLE` path recreates
them, which is the right outcome instead of a Dawn validation error.

- `SKBackend.cpp:1569-1570` -- nit (robustness): the check is keyed on `src->state->cc`, i.e. on
  the source having been *wrapped as a destination* once; a Skia surface that was never drawn to
  (`state == nullptr` or `cc == nullptr` after `DisposeContext`) bypasses it and
  `SKBackend_GetImage` wraps the texture with the current recorder. Unreachable today (every
  texture with content was a destination, and `DisposeContext` runs only when no Java object
  references the configuration), but the device comparison does not need the wrap state: compare
  `SKPlatform_ConfigOf(src->platform)` with `g.cc->config` and keep the submit for the
  `cc != nullptr && cc != g.cc` case.
- `SKBackend.cpp:751-754` -- nit (symmetry): `SKBackend_SetTexturePaint` has the cross-context
  submit but not the `SameDevice` check. Unreachable because `SKPaints.isTextureValid` goes
  through the destination's own proxy cache (`SKPaints.java:76`, one cache per configuration),
  but the same two lines belong there.
- `MTLSKPlatform.mm:78-89` -- clean: `deviceOf` returns nil for a configuration whose
  `MTLContext` is gone, which yields "not the same device" and a skip; the Skia disposal runs
  before the Metal one (`MTLGraphicsConfig.java:226-229`), so this never happens for a live
  surface.

### 1c. `e771f9cbdd05` `Win32SKScreenUpdateManager` and `requestDevice` limits

**GDI stand-in map (`gdiSurfaces`): lifetime clean.** Entries are created only in
`createGraphics` while the device is lost (`:116-120`), removed in `createScreenSurface` for the
surface being replaced (`:75-77`) and in `dropScreenSurface` (`:130-132`); every peer path that
abandons a surface calls one of them (`WComponentPeer.replaceSurfaceData:491-492` calls both,
`disposeImpl:675` the latter). The number of `replaceSurfaceDataLater()` posts is bounded by the
`getGraphics()` calls between the first lost detection and the EDT processing the first
replacement (after it the peer's surface is GDI and the branch at `:113` is not taken).

- `Win32SKScreenUpdateManager.java:76, 131` -- nit (precedent): D3D's `removeGdiSurface`
  (`D3DScreenUpdateManager.java:307-312`) calls `gdisd.invalidate()` when it drops the stand-in,
  so an outstanding `Graphics` on it goes through the replacement path instead of drawing to the
  window next to the new GDI surface, and the native DC is released before the Disposer. Do the
  same (`gdiSurfaces.remove(x)` returns the surface; invalidate it if non-null).

**`recover(gc)`: the window path works, verified end to end.** `recover` only posts
(`EventQueue.invokeLater`, `:144`), so calling it under the manager monitor plus the peer's and
tree locks (from `replaceSurfaceData`) and from any thread (`createGraphics` runs on whatever
thread calls `getGraphics()`) is safe. On the EDT `Win32GraphicsDevice.displayChanged()`
(`:545-555`) nulls `defaultConfig`/`configs` and notifies the top-level peers;
`WWindowPeer.displayChanged` always posts (`SunToolkit.executeOnEventHandlerThread:447-449`, a
`PeerEvent`, also when already on the EDT), `updateGC` calls
`getDefaultConfiguration()` -> `Win32SKGraphicsConfig.getConfig` -> `DawnSK_CreateConfig` (a new
device), then `setGraphicsConfiguration` -> `WComponentPeer.updateGraphicsData:549-561` ->
`replaceSurfaceData()` -> `createScreenSurface` with the new configuration; `previous` is the GDI
surface by then (not a `Win32SKSurfaceData`), the lost surface's swap chain was released when the
manager flushed it on the way to GDI (`:88-90` -> `FLUSH_SURFACE` -> `SKPlatform_DisposeSurface`
-> `Unconfigure()`), so the HWND takes a fresh swap chain on the new device. Duplicate posts are
prevented by `recovering` per configuration; a concurrent real `WM_DISPLAYCHANGE` is idempotent.
Nothing re-enters the manager from `displayChanged` while it holds its monitor.

**`Win32SKScreenUpdateManager.java:141-146` -- risk: the device is still lost after
recreation.** If `DawnSK_CreateConfig` finds no adapter or no device, `getConfig` returns null and
`Win32GraphicsDevice.getDefaultConfiguration` falls through to WGL/GDI: recovery ends on GDI,
correct. If a device *is* created and is lost again before its first surface (an adapter in a
TDR loop, a GPU removed between creation and use), the next `createScreenSurface` sees
`isDeviceLost()` on the new configuration and `recover(newGc)` posts again: an unbounded sequence
of `DawnSK_CreateConfig` calls on the EDT (adapter enumeration plus device creation each, tens to
hundreds of milliseconds), one per EDT round trip, each leaving another configuration in
`recovering`. *Fix:* bound it. Keep `int recoveryAttempts` in the manager, reset to 0 when
`createData` returns a Skia window surface, incremented in `recover`; after three consecutive lost
configurations stop calling `recover` (the process stays on GDI, one `J2dRlsTrace`/logger line).
**To verify** on real hardware with `dxcap -forcetdr` twice in a row.

**`Win32SKScreenUpdateManager.java:67, 142` -- risk (leak, one per device loss): `recovering`
holds every lost `Win32SKGraphicsConfig` strongly for the process lifetime.** Nothing else keeps
the old configuration alive after `displayChanged` (`defaultConfig` is nulled, `SKContext.contexts`
is a `WeakHashMap`), so the set is what prevents its Disposer record from running: the
`DawnSKConfigInfo`, the lost `wgpu::Device` and the native `SKConfigContext` (Graphite context,
glyph and mask caches, CPU side) are never freed. *Fix:* `Collections.newSetFromMap(new
WeakHashMap<>())`, or remove the entry when a replacement Skia surface has been created on a newer
configuration of the same device.

**`Win32SKScreenUpdateManager.java:144`, `SKVolatileSurfaceManager.java` (no
`restoreAcceleratedSurface`) -- risk (device loss only): VolatileImages of the lost configuration
are not moved and cannot restore themselves.** `Win32GraphicsDevice.displayChanged()` notifies only
the top-level peers (`topLevels`); the environment's `displayChanger` (VolatileSurfaceManagers,
`Win32GraphicsEnvironment.displayChanged:190`) is not run, so `SKVolatileSurfaceManager.displayChanged()`
never executes and `vImg.getGraphicsConfig()` stays the lost configuration. Then: a client that
validates against its component's configuration gets `IMAGE_INCOMPATIBLE` (`isConfigValid` is
identity-based, `SKVolatileSurfaceManager.java:68-70`) and recreates the image -- Swing's
`RepaintManager` keys its buffers per configuration anyway, so Swing recovers. A client that
validates with `null` or a cached configuration gets `sdAccel.isSurfaceLost()` true (B1 override)
-> `restoreAcceleratedSurface()`, which is the empty default (`VolatileSurfaceManager.java:339-340`)
-> `setSurfaceLost(false)` -> `IMAGE_RESTORED` -> `initContents()` into a dead texture, on every
`validate()` for ever; the image never appears (no hang; D3D overrides `restoreAcceleratedSurface`
to recreate the texture). *Fix, two parts:* (1) in `SKVolatileSurfaceManager` override
`restoreAcceleratedSurface()` as D3D/OGL do: `sdAccel.flush(); SurfaceData sd =
initAcceleratedSurface(); if (sd == null) throw new InvalidPipeException("..."); sdAccel = sd;` --
on a lost configuration `initAcceleratedSurface` returns null (the platform surface throws
`OutOfMemoryError`, caught at `:62`), `validate` then falls back to the software surface, which at
least renders; (2) in `recover`, post `GraphicsEnvironment.getLocalGraphicsEnvironment()`
(`Win32GraphicsEnvironment`) `.displayChanged()` instead of the device's: it is the entry the
toolkit uses for `WM_DISPLAYCHANGE`, runs on the EDT, recreates the device objects, notifies the
top-levels *and* `displayChanger.notifyListeners()`, so component-bound images move to the new
configuration (`vImg.updateGraphicsConfig()`, `SunVolatileImage.java:137-145`) and every `sdAccel`
is invalidated. Heavier than the per-device call, but it is exactly what a real display change
costs, and device loss is rarer.

**`DawnSKPlatform.cpp:152-159` requestDevice limits: correct.** Requiring the adapter's own limits
is valid WebGPU (required <= supported), the `Limits` local outlives the synchronous `WaitAny`,
and a default-constructed `wgpu::Limits` has no chained extension structs that `GetLimits` could
fill. It fixes the real mismatch: before, `maxTextureSize` was read from the adapter (16384) while
the device had WebGPU's 8192 default, so `DawnSK_CreateSurface` passed its own size check and
failed in `CreateTexture` for 8193..16384. **To verify** only that `DeviceDescriptor::requiredLimits`
is `Limits const *` in the pinned Dawn (the macOS prebuilt ships no generated `webgpu_cpp.h`; the
Windows session compiled it, so this is settled in practice).

- `DawnSKPlatform.cpp:204-205` -- nit: `gpu->maxTextureSize` is read from the *adapter* a second
  time; read `gpu->device.GetLimits(&limits)` so the value is what the device actually got and
  cannot desync from a later change of `requiredLimits`; the `2048` fallback is also below WebGPU's
  guaranteed 8192.

---

## 2. Whole-tree pass

### Risks

**R1. `Win32SKGraphicsConfig.getConfig` / `DawnSK_CreateConfig` -- risk (performance and
robustness on display changes, Windows): a new `wgpu::Device` per `displayChanged`.** Every
resolution or DPI change and every monitor plug nulls `defaultConfig`, and the next
`getDefaultConfiguration()` creates a new Dawn device, a new Graphite context on first draw, new
glyph and mask atlases, and makes every VolatileImage `IMAGE_INCOMPATIBLE`; `SKPlatform_SameDevice`
is false between the old and the new configuration, so the old images cannot even be blitted during
the transition (section 1b). D3D keeps its device across configurations. *Fix:* cache the
`std::shared_ptr<DawnSKDevice>` per adapter in `DawnSKPlatform.cpp` and reuse it in
`DawnSK_CreateConfig` unless `lost` is set; configurations then share the device (`SameDevice`
true, textures blittable), and the device-loss recovery still gets a fresh device because the
cache drops a lost one. **To verify:** count `DawnSK_CreateConfig` trace lines across a DPI change
on the Windows VM.

**R2. `SKSurfaceDataProxy.java:59-69` -- risk (low): `validateSurfaceData` returns a cached
texture that reports itself lost.** With B1 a cached `TEXTURE` on a lost device answers
`isSurfaceLost()` true; `SurfaceDataProxy.replaceData` then calls `validateSurfaceData(src,
cachedSD, ...)`, which returns the same dead surface when non-null, and both the sw->texture and
texture->surface blits no-op. Practically invisible today (proxies are per configuration and the
lost configuration's windows are on GDI, whose destinations use their own proxy cache), but D3D's
proxy recreates on loss (`D3DSurfaceDataProxy.validateSurfaceData`). *Fix:* `if (cachedData ==
null || cachedData.isSurfaceLost()) { if (cachedData != null) cachedData.flush(); cachedData =
gc.createSKSurfaceData(...); }`.

### Nits

- `MTLGraphicsConfig.java:172-180` -- `getContext()` takes the `SKEnvironment` class monitor on
  every call (`isSkiaEnabled()` is `static synchronized`). Uncontended and off the hot path
  (`MTLSurfaceData.getContext()` now uses `getMTLContext()`, no other caller in `sun.java2d.metal`
  or `sun.lwawt`), but the value never changes after initialization: cache it in a
  `private static final boolean` in `MTLGraphicsConfig`, or make `isSkiaEnabled()` read a
  `volatile` after the first `init()`.
- `Win32SKSurfaceData.java:65-73, 176-180` -- if `initOps` throws after `createPlatformSurface`
  succeeded (the `SurfaceData_InitOps` allocation fails), the `DawnSKSurface` has no ops to be
  disposed through and leaks. Edge of an edge; a `try`/`catch` around `super(...)` is not possible,
  so either accept it or create the platform surface in a static factory after `initOps`.
- `SKEnvironment.java:77-83` -- `defaultsFor(String gpuApi)` still ignores its parameter
  (carried from round 2; fine if the Vulkan sentence stays).
- `lib-skia.m4:73` -- `ls -d out/*/ | head -n 1` still picks the first `out` directory; prefer
  `out/${SKIA_BUILD_TYPE}-*` (carried).
- `MTLTextRenderer.m:131` -- `MTLTR_CACHE_HEIGHT` referenced only by a comment (carried).

### Clean

- Memory and thread safety: every global in `SKBackend.cpp` (`gContexts`, `g`, `gRects`, the
  `xorBlender`/`lcdBlender` statics, the `opaque` upload buffer, `gAdding` in `SKGlyphCache.cpp`)
  is touched on the flusher thread only, and every JNI entry that renders either is the flusher
  (`flushBuffer`) or runs there via `flushAndInvokeNow`; the EDT's device use (`createPlatformSurface`)
  happens under the AWT lock, under which the flusher cannot run (its timed flush needs `tryLock`,
  a `flushNow`-driven flush runs on behalf of the lock holder). Critical arrays are released on
  every path with no JNI call in between; `ReadbackRequest` refcounting unchanged; `SKSurfaceState`,
  `SKConfigContext`, `DawnSKSurface`, `DawnSKConfigInfo` each have exactly one owner and one delete.
- Protocol against `BufferedContext`: `SKContext.validateContext` invalidates on a context switch;
  `BufferedContext.currentContext` is shared with the Metal/OGL contexts, and the only consequence
  of a foreign write is an extra invalidation (the safe direction). `FLUSH_SURFACE`/`DISPOSE_SURFACE`
  ordering, `SET_SCRATCH_SURFACE` before disposals, `DISPOSE_CONFIG` before the platform frees the
  info: unchanged and correct on both platforms.
- Flag-off neutrality: nothing in the diff executes without `-Dsun.java2d.skia=true`; the hooks
  cost one `static synchronized` boolean each (`Win32GraphicsDevice:219, 314`,
  `ScreenUpdateManager:130`, `WToolkit:641`, `LWCToolkit:567`, `MTLGraphicsConfig:176, 226, 243,
  350`), `MacOSFlags` reads the property directly, `WindowsFlags` touches `d3dEnabled` only when
  the flag is on, and the Java classes are not loaded otherwise except `SKEnvironment`.
- Build integration: `--with-skia` on Linux now errors; `SKIA_ENABLED=false` leaves `AwtLibraries.gmk`
  untouched; macOS exports list hides every non-JNI symbol; Windows link list carries the D3D/DXGI
  import libraries; `-MT` boundary unchanged from round 2 (still **to verify** once with a
  debug-CRT build); `OPTIMIZATION := LOW` for both pipelines, to be recorded with the run.
- Headers: all 40 new source files carry GPLv2+CPE with the JetBrains line; `fetch_skia.sh`,
  `skia_prebuilt.conf`, `libawt_skia.exports` have none (repo precedent `common.sh`);
  `lib-skia.m4:2` "JetBrains s.r.o.." follows `lib-wayland.m4:3`.
- Naming: `SK*` portable, `MTLSK*`, `DawnSK*`, `Win32SK*`, consistent; new identifiers
  (`drawPerQuad`, `recovering`, `gdiSurfaces`, `SKPlatform_SameDevice`) spelled out.
- Dead code: none found (`SKBackend_CurrentCanvas`, `forgetSurface(dispose)`, `backendType`,
  `nativeStartRedraw` all gone; every `SKPlatform_*` has a caller on both platforms).
- Debug output: `System.err` under `sun.java2d.skia.verbose` (`SKEnvironment.java:143, 154`) and
  `System.out` under `sun.java2d.skia=True` (`WindowsFlags.java:205`) follow `MacOSFlags`/`WindowsFlags`
  precedent; no `printf`/`NSLog`/`TODO`/`FIXME` in the diff.
- Comments: describe the code, not its history; the three forward-looking remarks
  (`Win32SKGraphicsConfig.cpp:40-41` multi-adapter, `MTLSKPlatform.mm:68-69` `MakeMetal` vs
  `MakeGraphite`, `AwtLibraries.gmk:651-653` Linux version script) are statements about the current
  API, acceptable.
- Non-ASCII characters: none in any of the 63 changed files.

## 3. References to material outside the repository

None. Grep over every added line for ledger/matrix/probe/session/review/round/dates: no hits.
`SKEnvironment.java:78` ("Measured on Metal (Apple M1 Max) and Dawn/D3D11") and `SKQuadBatch.h:66`
("measured on D3D11") are measurement statements; `skia_prebuilt.conf:5` is a provenance note.
`JBR-10654` appears five times (`SKBackend.h:28`, `SKSurfaceData.java:55`, `SKEnvironment.java:31`,
`SKRenderQueue.java:35`, `AwtLibraries.gmk:627`), issue references, tolerated.

## 4. The Metal switches commit (`d723fffd2196`)

Defaults re-verified identical to shipped behaviour against the final tree. `MTLOptions.m`: every
option is read once, the default is returned uncached when no JNI environment is available;
`coalesceCommits` unset -> `canCoalesceCommit` returns NO -> `nonCoalescable` is `YES |= YES` and,
after the `= NO` on a commit, `NO |= YES` again before the next predicate, so
`MTLRenderQueue.m:188-195` reduces to the original `(SYNC || SHAPE_CLIP_SPANS || RESET_PAINT ||
prev == MASK_OP)` with the original wait flag `(SYNC || SHAPE_CLIP_SPANS)`. `glyphCacheSize` 0 ->
`MTLTR_CACHE_WIDTH` (512); `glyphCacheSync` YES -> `commitCommandBuffer:YES`;
`gpuOptimizedTextures` YES -> descriptors untouched. `MTLGlyphCache.m:112-117` frees its own
`malloc`ed struct on a nil texture. The round-2 wording nit is resolved: `MTLOptions.h:33-36` now
says "SrcOver with alpha (COMP_ALPHA)", which is what `canCoalesceCommit` tests. Ready.

## 5. Overall assessment

**(a) CI performance run.** macOS: ready; the only behavioural change since round 2 on the default
paths is none (LCD text and XOR are not default on macOS; `SameDevice` and `recover` do not execute
without a second GPU or a lost device). Windows: ready, with one caveat that should be decided
*before* the run rather than after: `drawPerQuad` puts the default text mode (LCD) on a
one-draw-and-one-pass-per-glyph path (1a). Either land the overlap-only split first, or run as is
and record that text numbers include it, because a later change will move them. Device-loss items
(1c, R2) only matter if a TDR happens during the run. Record `OPTIMIZATION := LOW`.

**(b) First upstream pull request (experimental, flag-guarded).** The tree is coherent and
flag-off-neutral: nothing executes without `-Dsun.java2d.skia=true` and `--with-skia`, every hook
in a shared class is one `if`, headers, naming and comments are in order, and no reference points
outside the repository. Before opening: decide 1a (overlap split or a sentence in the PR stating the
per-glyph cost on Windows LCD text), add the `restoreAcceleratedSurface` override and bound
`recover` with a weak set (1c; together about twenty lines), and apply the two-line nits in
`SKBackend_Blit`/`SetTexturePaint`. R1 (device per display change) and R2 can be follow-ups if
the PR lists them as known limitations together with: cross-GPU blits are skipped (as the Metal
pipeline does), one Skia configuration per process on Windows (default device), device loss
recovers windows and configuration-validated VolatileImages but not images validated with a null
configuration.
