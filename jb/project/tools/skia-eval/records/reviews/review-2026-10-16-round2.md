# Review round 2 of `manuel.unterhofer/JBR-10654-skia-backend` (HEAD 36d64d2cdbd9 vs `main`)

Date: 2026-10-16. Scope: the resulting tree of 19 topical + 7 `fixup!` commits (64 files,
+8160/-36), read against the first review (`build/notes/review-2026-10-16.md`). Every statement
below was checked against the sources at HEAD; what could not be settled from the tree is marked
**to verify** with the evidence that would settle it. Severity: **bug** (wrong today under a
reachable condition), **risk** (latent, or correct on an unstated assumption), **nit** (quality).

## 0. Status of the first review's findings

| Item | State at HEAD |
|---|---|
| R1 dangling `g.canvas` on re-wrap | fixed in `GetSurface`, but the fix breaks `SetDestination` (new finding A1) |
| R2 cross-device sources (Windows) | still wraps with the destination's recorder; reachable only transiently (A6) |
| R3 unbounded opaque buffer | fixed: buffer dropped above 4096² (`SKBackend.cpp:707-709`) |
| R4 direct `drawShape` without `params` | fixed: entry restricted to `KIND_PATH` (`SKRenderQueue.cpp:471-476`, `SKShapePipe.java:434-438`) |
| R5 flushed window left in `touchedWindows` | fixed (`SKBackend.cpp:331`) |
| R6 Metal frees the texture after a non-blocking submit | documented at `SKBackend.cpp:261-264`; still **to verify** against `MtlTexture.mm` (the prebuilt under `build/deps/skia` has `src/core`, `src/gpu/ganesh`, `src/utils` only, no Graphite sources) |
| R7 presentation vs. Skia queue | "fixed" by a `tryLock` that can never fail; the premise was wrong, see A2 |
| R8 `layer` cleared without lock | fixed: `MTLSKSurfaceData.invalidate()` releases the Skia wrapper first |
| R9 mirrored structs | fixed: `MTLSurfaceDataBase.h:83-112`, both libraries include it |
| R10 Dawn error objects | fixed: error scopes around `CreateTexture` and `Configure` |
| R11 two flip chains per HWND | fixed: `FLUSH_SURFACE` disposes WINDOW platform surfaces, `createSurfaceData` returns GDI, fallback flushes `previous` |
| R12 off-screen textures without lock | fixed: `rq.lock()` plus `ImplicitDeviceSynchronization` when offered |
| R13 no device-lost handling | partly: `lost` flag, GDI fallback for windows; no recovery and no VolatileImage/texture loss (B1, A3) |
| R14 raw `config` pointer | fixed: `shared_ptr<DawnSKDevice>` held by every surface |
| R15 `d3d=true` + `skia=true` | fixed (`WindowsFlags.java:201-209`) |
| R16 Linux configures but cannot build | **not fixed**: the new error covers only OSes other than macOS/Linux/Windows (A5) |
| R17 coalescing gate inspects state not work | fixed: sticky `nonCoalescable` |
| R18 `glyphCacheSize` unvalidated | fixed: range + multiple-of-32 check, nil texture returns NO |
| B1 `WeakHashMap` leak, B2 `opaque` shadowing | fixed (map removed; `opaqueSupported`) |
| External references | all ledger/matrix/date references gone; one probe reference remains (section 6) |

---

## 1. Native code

### Bugs

**B1. `Win32SKSurfaceData.java` (no override), `Win32SKGraphicsConfig.java:113-115`,
`VolatileSurfaceManager.java:198` — bug (device loss only): off-screen Skia surfaces never report
themselves lost.** The `lost` flag reaches Java only through `isDeviceLost()`, which only
`Win32SKScreenUpdateManager.createGraphics` consults for *window* surfaces. A `Win32SKSurfaceData`
of type `RT_TEXTURE`/`TEXTURE` never calls `setSurfaceLost(true)`, so after a device loss
`VolatileImage.validate()` keeps returning `IMAGE_OK` (`VolatileSurfaceManager.validate` checks
`sdAccel.isSurfaceLost()`), the image's contents are never restored, every draw into it is a no-op
(`SKPlatform_TextureHandle` returns null, `SKBackend_SetDestination` leaves `g.canvas == nullptr`),
and `SKSurfaceDataProxy` keeps handing out dead textures as sources. D3D does this via
`D3DSurfaceData.setSurfaceLost` (`D3DSurfaceData.java:262, 577, 1028`). *Fix:* in
`Win32SKSurfaceData` override `isSurfaceLost()` to `return super.isSurfaceLost() || isDeviceLost();`
(one override covers VolatileImages, proxies and the manager's window check); the restore path then
goes through the normal `VolatileSurfaceManager` machinery once the configuration is recreated (A3).

### Risks

**A1. `SKBackend.cpp:375-399` with `:205-213` — risk (latent): the R1 fix nulls `g.dst` inside
`SetDestination`.** `SetDestination` assigns `g.dst = dst` (`:377`) and then calls
`SKBackend_GetSurface(dst)` (`:383`). In the re-wrap branch `GetSurface` calls
`forgetSurface(ops, false)`, which sees `g.dst == ops` and sets `g.dst = nullptr`. Back in
`SetDestination`, `g.canvas` is taken from the new surface but `g.dst` stays null: `markTouched()`
never adds the window, so the frame is not presented until the next `SET_SURFACES`; the
`FLUSH_SURFACE` comparison with `SKBackend_CurrentDestination()` misfires; `SKBackend_CopyArea`
calls `GetSurface(nullptr)` and returns. Unreachable today for the same reason R1 was (the texture
of a surface never changes while its wrapper lives; `DisposeContext` resets `st->surface`, so the
next `GetSurface` takes the first-wrap branch), but the fix turned a dangling pointer into a lost
destination. *Fix:* in `SetDestination` take the surface first and assign `g.dst` after
(`sk_sp<SkSurface> surface = GetSurface(dst); g.dst = surface ? dst : nullptr;`), or let
`GetSurface` only reset `g.canvas` and leave `g.dst` to its caller.

**A2. `MTLLayer.java:149-176`, `MTLLayer.m:704-714` — risk (dead code, misleading comment): the
Skia "try lock" can never fail.** `RenderQueue.lock()`/`tryLock()` for *every* queue are
`SunToolkit.awtLock()`/`awtTryLock()` (`RenderQueue.java:116, 124-126`), one process-wide
`ReentrantLock` (`SunToolkit.java:224`). `drawInMTLContext` already holds it via `rq.lock()`, and
`ReentrantLock.tryLock(0, NANOSECONDS)` succeeds for the owner, so `skrq.tryLock()` always returns
true, the `nativeStartRedraw` branch is dead, and the comment's lock-order argument ("the rendering
thread takes the Skia lock before the Metal lock") describes two locks where there is one. The
same fact disposes of the original R7: the EDT holds the AWT lock for the whole synchronous
`flushNow()` (`SKRenderQueue.QueueFlusher.flushNow`), and the flusher's own timed flush takes it via
`tryLock()` (`SKRenderQueue.java:164`), so `blitTexture` cannot run between two submissions of one
Skia flush. *Fix:* delete the `tryLock` branch, `nativeStartRedraw` and its JNI function, and
replace the comment with one line: "the Metal and Skia queues share the AWT lock, so the blit
never interleaves a flush of either".

**A3. `Win32SKScreenUpdateManager.java:57, 84-91`, `Win32SKGraphicsConfig.java:107-115` — risk:
no recovery from a device loss without a display change.** After `lost` is set, windows go to GDI
(`createGraphics` → `replaceSurfaceDataLater`), and the Javadoc says the display change recreates
the configuration (`Win32GraphicsDevice.displayChanged` resets `defaultConfig`). A driver reset
(TDR) or a GPU removed by the OS does not necessarily post a display-change event, so the process
stays on GDI (and, until B1 is fixed, with dead VolatileImages) for its lifetime. D3D re-creates its
device in `D3DScreenUpdateManager.validate` → `D3DGraphicsDevice.restoreDevices`. *Fix:* when
`createScreenSurface` sees `skgc.isDeviceLost()`, drop the device's cached configuration
(`Win32GraphicsDevice` has no public hook; `displayChanged()` does it and also notifies the
top-levels, which is what is wanted) so the next `getDefaultConfiguration()` builds a new
`DawnSKConfigInfo`/`wgpu::Device`; the peer's `replaceSurfaceData` then lands on a live Skia config.
**To verify** with `dxcap -forcetdr` on real hardware.

**A4. `SKQuadBatch.h:68-76`, `SKBackend.cpp:1374-1377, 1404-1408` — risk (visual, Windows only, to
verify): glyph lists under a runtime blender are one draw per *list*, not per glyph.** The batch
draws pending quads at every `begin()` when the paint has a runtime blender, so XOR/LCD
*primitives* get their own draw; but `SKGlyphCache_Begin` is called once per glyph list and the
list's sprites share one `drawVertices`. Where Graphite reads the destination through a copy taken
before the draw (no framebuffer fetch; the comment at `:70-72` says this is the case for Dawn on
D3D11), two glyphs of one list whose boxes overlap (kerned pairs, italics, LCD glyphs shifted by
`fx += 1`) both see the destination from before the draw, and the second overwrites the first's
coverage at the overlap. On Metal (Apple GPUs, framebuffer fetch) this does not arise, which is why
the correctness matrix did not show it. *Evidence:* `DawnCaps::fDstReadStrategy` in Skia m156
(`kTextureCopy` vs `kFramebufferFetch`); render "AVfi" in LCD mode on the Windows VM and diff
against GDI. *Fix if confirmed:* in `SKGlyphCache_Draw`, call the batch's `drawPending()` (expose
it) before `addSprite` when `!paint.asBlendMode().has_value()`, i.e. one draw per glyph on those
paths, as `MTLTextRenderer`'s LCD path does.

**A5. `lib-skia.m4:111-112, 131-133` — risk (build): `--with-skia` on Linux still configures and
fails to link.** The new `AC_MSG_ERROR` fires for OSes other than macOS, Linux and Windows; the
Linux branch still sets `-DSK_VULKAN -std=c++17`, and there is no `src/java.desktop/unix/native/libawt_skia`
(checked), so `libawt_skia` compiles `share/` and fails on `SKPlatform_*` and
`Java_sun_java2d_skia_SKEnvironment_initNative`. R16 is not fixed. *Fix:* route `linux` into the
error (keep the `SKIA_SHA256_linux_*` pins; `fetch_skia.sh` is independent of configure) until a
Vulkan half exists.

**A6. `SKBackend.cpp:235-251, 748-752, 1566-1578` — risk (low, Windows): cross-context sources are
still wrapped with the destination's recorder.** On Windows every `Win32SKGraphicsConfig` owns a
`wgpu::Device`; a texture of device A wrapped on device B is a Dawn validation error reported
asynchronously (nothing drawn, error callback only). Reachable only in the window between
`displayChanged` creating a new configuration and `VolatileSurfaceManager.displayChanged`
invalidating the old images (proxies are per-config caches, so they are not affected), because
only the default device gets a Skia configuration. *Fix:* when `src->state->cc != g.cc`, compare
the platform devices (`SKPlatform_ConfigOf` → a new `SKPlatform_SameDevice(a, b)`) and skip the blit
with a trace, or read back and re-upload; the synchronous submit at `:1568` stays.

**A7. `SKBackend.cpp:401-406` with `:119-146` — risk (perf/startup, nit-level): `SET_SCRATCH_SURFACE`
creates a Graphite context as a side effect.** `SKBackend_SetScratch` calls `SKBackend_ContextFor`,
which builds a `Context` + `Recorder` if none exists. `SKRenderQueue.disposeGraphicsConfig` and
`SKSurfaceData.releaseSkiaSurface` both send `SET_SCRATCH_SURFACE` first, so a configuration that
never rendered (e.g. the one replaced on a display change) gets a full Graphite/Dawn context created
and destroyed in the same flush. Harmless but expensive on Dawn. *Fix:* look up without creating in
`SetScratch` (`gContexts.find`), or give `ContextFor` a `create` flag.

### Nits

- `SKBackend.cpp:326-333` — `forgetSurface(ops, dispose)`: `dispose` is unused (`(void) dispose`). Drop the parameter.
- `SKBackend.h:103`, `SKBackend.cpp:414` — `SKBackend_CurrentCanvas()` has no caller.
- `SKBackend.cpp:365-366` — "verified with a standalone Graphite probe" refers to an artefact outside the repository; say what was verified ("`UploadInstance::Make` copies the pixels into the upload buffer while recording").
- `DawnSKPlatform.h:57` — `DawnSKConfigInfo::backendType` is written at `.cpp:200` and never read (`describe` takes it as a parameter). Remove or use it in `backendName` traces.
- `DawnSKPlatform.cpp:430-435` — a `Lost` swap-chain status (as opposed to a lost device) only warns per flush; the window recovers at its next bounds change. Acceptable; a comment saying so would stop a reader from filing it.
- `SKQuadBatch.h:63`, `MTLSKSurfaceData.java:39`, `SKEnvironment.java:48-49`, `Win32SKSurfaceData.java:54`, `SKBlitLoops.java` (two Javadoc arrows) — non-ASCII dashes/arrows; native code in the repository is ASCII, at least fix the `.h`.
- `SKEnvironment.java:76-82` — `defaultsFor(String gpuApi)` ignores its parameter (branches collapsed as suggested; keep the parameter only if the comment keeps promising per-API values).
- Windows `-MT` CRT (`lib-skia.m4:119-124`) — no cross-CRT `free` found: `SKSDOps` are allocated and freed by `libawt` (`SurfaceData_InitOps`/`DisposeOps`), `libfontmanager`'s `AccelGlyphCache_RemoveAllCellInfos` only unlinks cells (`AccelGlyphCache.c:373-386`), `common/font` now contains only `AccelGlyphCache.c` (no `sunfontids.c`, so no duplicate JNI symbol). **To verify** once with a debug-CRT Windows build (`_CrtIsValidHeapPointer` asserts would catch a stray one).

### Clean

- `SKRenderQueue.cpp` opcode decoding unchanged and correct; `FLUSH_SURFACE` ordering (invalidate state → release wrapper → dispose platform surface of WINDOW ops) is right on both platforms: on macOS `SKPlatform_DisposeSurface` is a no-op and `ops->platform = nullptr` is harmless because both replace paths never touch the old surface again (`LWWindowPeer.replaceSurfaceData:996-999` flushes it, `disposeImpl:243-247` invalidates it; the blit of old contents happens before); on Windows the swap chain is released synchronously before the successor or GDI takes the HWND.
- Swap-chain hand-over: `createData(peer, …)` holds the queue lock and flushes before `new Window(...)`; `previous` is the peer's still-valid surface (`WComponentPeer.createScreenSurface:462` assigns after the manager returns); a previously flushed `previous` yields `platform == nullptr` → a fresh `wgpu::Surface` on an HWND whose chain was `Unconfigure()`d. The failure path unconfigures the taken-over chain and the manager flushes `previous`, so no chain is left behind.
- `DawnSKDevice` sharing: `adapter/device/queue` by value (refcounted), `lost` an `atomic<bool>` behind a `shared_ptr` captured by the device-lost callback, read on EDT and flusher without further state. `DawnSK_DisposeConfig` after a surface's disposal is safe (`SKPlatform_DisposeSurface` dereferences only `s->gpu`, `s->window`, `s->texture`).
- Error scopes: push OOM then Validation, pop twice with `WaitAny`; since every device use happens under the AWT lock (the flusher runs only while a lock holder waits in `flushNow`, or after its own `tryLock`), Graphite's own error scopes on the flusher cannot interleave with surface creation on the EDT; `ImplicitDeviceSynchronization` is belt and braces, as the comment says.
- Present: `GetCurrentTexture` → reconfigure on `Outdated` → copy on the device's queue → `Present`, all on the flusher after Graphite's submit on the same queue.
- `SKQuadBatch`: colour promotion (`colors.assign(positions.size(), color)`) happens before the first differently coloured quad; the 16-bit split keeps `colorPerVertex` consistent; `paint == key` compares every paint field so alpha and blend differences end a batch; the `SkASSERT` for mixed textured/untextured batches is in. No `SkPaint` copy on the plain path, one copy per atlas primitive (measured, per the comment).
- `SKSurfaceData.releaseSkiaSurface`: idempotent under the lock, synchronous, sends `SET_SCRATCH_SURFACE` first like `MTLSurfaceData.flush`; `flush()` → `invalidate()` matches `MTLSurfaceData.flush:476-477`.
- Readback (`SurfaceToSwBlit`): `FlushRecording` drains every batch before `asyncRescaleAndReadPixels`; request refcounting unchanged.
- `SKGlyphCache`/`SKMaskCache`: eviction and wrap-around end the recording before overwriting; the mask cache's `beforeDraw` upload runs for every draw including the ones `begin()` triggers for a paint change.
- JNI: critical arrays released on every path; no JNI calls while held; `SKPlatformScope` (an `NSAutoreleasePool` under MRC) brackets every rendering entry.

---

## 2. Java side

### Bugs

None beyond B1 (listed under native since the flag originates there).

### Risks

**A8. `Win32SKScreenUpdateManager.java:86-89` — risk (resource churn): a new `GDIWindowSurfaceData`
per `getGraphics()` while the device is lost.** `createGraphics` creates a GDI surface for each
`Graphics` until `replaceSurfaceDataLater` has run; each carries native ops freed by the Disposer.
`D3DScreenUpdateManager.getGdiSurface` caches one per window (`:80, 242, 307-311`). Short window,
but a repaint storm during the swap can create dozens. *Fix:* cache per peer as D3D does, or
return `super.createGraphics(peer.getSurfaceData(), …)` once the replacement happened.

### Nits

- `MTLLayer.java:149-176` — see A2 (dead branch, native method `nativeStartRedraw` removable).
- `MacOSFlags.java:35-38` — the usage comment lists only `sun.java2d.metal`; `sun.java2d.skia` and the four Metal switches are not documented anywhere in Java. One line each.
- `Win32SKScreenUpdateManager.dropScreenSurface` — an intentional no-op with a comment; fine.

### Clean

- Protocol against `BufferedContext`: `SKContext.validateContext` invalidates the per-context cache on a context switch (needed because the native render state is one for all configs); `SKSurfaceData.context` is now a final field, so the per-primitive lookup from the first review is gone.
- Flag-off behaviour unchanged on both platforms: `MacOSFlags` reads the property directly; `MTLGraphicsConfig` branches on `SKEnvironment.isSkiaEnabled()` (false after one class load); `MTLSurfaceData.getContext()` → `getMTLContext()`; `Win32GraphicsDevice.getDefaultConfiguration` tries Skia only when enabled and on the default device, then falls through to the WGL/GDI code unchanged; `ScreenUpdateManager.getInstance` and `WToolkit.sync` gated on the same flag; `WindowsFlags` touches `d3dEnabled` only when `sun.java2d.skia=true`.
- Graphics-config selection: one Skia config per process (default device), new config on `displayChanged`; `Win32GraphicsDevice.getConfigurations` returns the single accelerated config like the OGL path.
- `SKGraphicsConfig` defaults: `colorModelFor` equals the WGL/MTL models (24-bit RGB, 25-bit bitmask, premultiplied sRGB ARGB); `createSKVolatileImage` equals `MTLGraphicsConfig.createCompatibleVolatileImage` (type check through `getDestSurface`, `flush()` on mismatch); `ContextCaps` omits `CAPS_RT_PLAIN_ALPHA`/`CAPS_PS20`, which nothing in the Skia path reads; `ImageCaps.isTrueVolatile() == true`.
- `SurfaceDataProxy` lifetime: one `ProxyCache` per config; `SKSurfaceDataProxy.createProxy` returns `UNCACHED` for Skia sources; `validateSurfaceData` catches the `OutOfMemoryError` the Windows `platformSurface` throws.
- Disposer paths: config disposer runs the Skia `DISPOSE_CONFIG` before the platform frees the info on both platforms; surface disposal after config disposal is safe (`state->cc` nulled by `DisposeContext`); `retainedByOps` keeps the Metal delegate alive until `DISPOSE_SURFACE` ran.
- Lock pairing and order: every `lock()` has a `finally { unlock() }`; manager → peer → AWT lock matches the D3D manager; the flusher never calls back into Java under the lock.

---

## 3. Build integration

- **A5** (Linux) above is the one open item.
- `lib-skia.m4:73` — `ls -d out/*/ | head -n 1` still picks the first `out` directory (carried over). Prefer `out/${SKIA_BUILD_TYPE}-*`.
- `lib-skia.m4:154-155` — `AC_SUBST(SKIA_INCLUDE_DIR)`/`AC_SUBST(SKIA_LIB_DIR)` have no consumer since the fixup removed them from `spec.gmk.template`. Drop.
- `fetch_skia.sh`, `skia_prebuilt.conf`, `libawt_skia.exports` — no copyright line; local precedent (`common.sh`) has none either.
- Clean: nothing changes without `--with-skia` (`SKIA_ENABLED=false`, gmk block skipped); `-std=c++17`/`-std:c++20` after `CXXFLAGS_JDKLIB` wins on clang and MSVC; `-DSK_RELEASE` matches the Release prebuilt for debug JDK builds; the pin check warns rather than fails for hand-built Skias; `EXTRA_SRC := common/font` now pulls in only `AccelGlyphCache.c` (third copy, unlink-only across libraries); the macOS exports list hides every non-JNI symbol; Windows link list includes the D3D/DXGI import libraries Dawn needs; `OPTIMIZATION := LOW` is still the (even) choice for both pipelines, record it in the PR.

---

## 4. The Metal measurement-switches commit (`74d22c4836ed` + fixup `2f516bc014c6`)

Defaults re-verified identical to the shipped behaviour: with `coalesceCommits` unset
`canCoalesceCommit` returns NO, `nonCoalescable` is `YES |= YES`, and the predicate at
`MTLRenderQueue.m:189-193` reduces to the original `(SYNC || SHAPE_CLIP_SPANS || RESET_PAINT ||
prev == MASK_OP)` with the original wait flag `(SYNC || SHAPE_CLIP_SPANS)`. `glyphCacheSize` 0 →
`MTLTR_CACHE_WIDTH` (512); `glyphCacheSync` YES → `commitCommandBuffer:YES`; `gpuOptimizedTextures`
YES → descriptors untouched.

- Sticky flag (`:186-194`): set from the state at every `CheckPreviousOp`, i.e. before each draw op with its state already applied (`SET_*` ops precede the draw in the buffer), cleared only on the two optional-commit paths. Commits made elsewhere (`MTLRQ_FlushBuffer` end, blits, text flushes) leave it YES, which costs at most one extra optional commit; the safe direction. R17 fixed.
- Size validation (`MTLOptions.m:58-70`): `[value intValue]` → 0 for garbage → default; range `[32, 16384]`, multiple of 32. `MTLGlyphCache.m:112-117` returns NO on a nil texture and frees the `MTLGlyphCacheInfo` it just allocated (its own struct, `free` is right). R18 fixed.
- Headers now GPLv2+CPE with the JetBrains copyright line; no non-ASCII characters remain in `libawt_lwawt/java2d/metal`; the dated and Skia-relative wording is gone from `MTLOptions.h:31-33` and `MTLRenderQueue.m:57-63`.
- Nits still open: `MTLRenderQueue.m:67` excludes `COMP_ISCOPY` (opaque colour under SrcOver), so the most common paint state is not coalesced while the header promises "SrcOver colour/gradient painting" — either include `COMP_ISCOPY` (and re-run the rendering matrix) or say "COMP_ALPHA SrcOver" in the header. `MTLTextRenderer.m:46-47`: `MTLTR_CACHE_HEIGHT` is referenced only by a comment. `MTLOptions.m:50-51` `isCaseInsensitiveLike:` follows `MTLLayer.m` precedent.

---

## 5. Quality items a JBR reviewer would raise

- Dead code: A2 (`tryLock` branch + `nativeStartRedraw`), `SKBackend_CurrentCanvas`, `forgetSurface`'s `dispose` parameter, `DawnSKConfigInfo::backendType`.
- Debug output: none (`System.err` under `sun.java2d.skia.verbose` and `System.out` under `sun.java2d.skia=True` follow `MacOSFlags`/`WindowsFlags` precedent; no `printf`/`NSLog`/`TODO`/`FIXME` in the diff).
- History-describing comments: none left; `SKRenderQueue.cpp:286-288`, `SKBackend.cpp:388-391`, `DawnSKPlatform.cpp:309-314` describe the code.
- Duplication between halves: the Windows config now uses the `SKGraphicsConfig` defaults; `MTLSKEnvironment.mm`/`DawnSKEnvironment.cpp` each build the same `"Skia m<N> Graphite/<API> on <device>"` string (a `SKEnvironment_Describe(api, device)` in `SKEnvironment.cpp` would remove the last copy; nit).
- Naming: consistent (`SK*` portable, `MTLSK*`, `DawnSK*`, `Win32SK*`; `SKSurfaceData_PlatformOf` added as suggested).
- Headers: all 42 new source files carry the GPLv2+CPE header with the JetBrains line; the three scripts/config files have none (section 3).

## 6. References to material outside the repository

| File:line | Text | Action |
|---|---|---|
| `src/java.desktop/share/native/libawt_skia/SKBackend.cpp:365-366` | "verified with a standalone Graphite probe" | state the verified fact instead |

Everything else from the first review's table is gone. `SKEnvironment.java:77` ("Measured on Metal (Apple M1 Max) and Dawn/D3D11") and `SKQuadBatch.h:66` ("measured on D3D11") are measurement statements, not references; `skia_prebuilt.conf:5` ("verified locally for macos-arm64") is a provenance note. `JBR-10654` remains in `SKBackend.h:28`, `SKSurfaceData.java:55`, `SKEnvironment.java:31`, `SKRenderQueue.java:35`, `AwtLibraries.gmk:627` (issue references, tolerated).

---

## 7. Overall assessment

**CI performance run.** macOS: ready. The two throughput-relevant items of the first review (opaque
buffer cap, per-primitive `SKContext` lookup) are fixed; A1/A2 do not execute on the hot path; A7
costs one context creation per disposed configuration, not per frame. Windows: ready for a run that
does not exercise device loss; the swap-chain leak is gone, surface creation is locked, Dawn
failures now produce a GDI fallback instead of a silent black window. B1/A3 only matter if a TDR
happens during the run (then the run should be discarded anyway). A4 could affect a
screenshot-based LCD comparison on Windows, not timings. Record `OPTIMIZATION := LOW` in the run's
notes.

**First upstream PR of the Skia backend (experimental, flag-guarded).** Fix before opening: A1 (two
lines), A2 (delete the dead branch and the JNI method; the reviewer will otherwise find the
non-existent lock order), A5 (Linux must error), B1 (`isSurfaceLost` override), the probe comment
(section 6), and the dead-code nits. Decide and document A3 (recovery after device loss) as a known
limitation if it is not implemented, and A4 once verified on the Windows VM. With those the branch
is a coherent, reviewable, flag-off-neutral change: nothing in the diff executes without
`-Dsun.java2d.skia=true` and `--with-skia`, and the hooks in shared JDK classes are one `if` each.

**Metal switches PR.** Ready; the only remaining question is the `COMP_ISCOPY` exclusion, which
should be either included or named in the header before a reviewer asks.
