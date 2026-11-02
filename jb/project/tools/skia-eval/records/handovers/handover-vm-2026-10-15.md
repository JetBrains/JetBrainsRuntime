# Hand-over: Skia backend on Windows (Parallels VM) — 2026-10-15

Read `skia-rendering-ledger.md` first (§0 status, §10 decisions, §13 open items, §17 design, §19 measurement plan,
§12 work log — the last entries describe the platform split this note builds on). This note only adds what the
Windows session needs. Keep the ledger updated from the VM too (it is git-ignored; the Mac copy is the master until the
sessions are reconciled — write to a dated section and merge later, or edit it through the shared folder).

## Where to find things from the guest

Parallels shares `~/Projects` read-write and the Mac home directory into the guest (`\\Mac\Projects\…`,
`\\Mac\Home\…`; a mapped drive letter may exist). Read this note and the ledger in place from
`\\Mac\Projects\JetBrainsRuntime\build\notes\`. Do **not** build in the shared folder: clone the fork onto the
guest's own disk. Memory files for the new Claude session: `\\Mac\Home\.claude\projects\-Users-manuel-unterhofer-Projects-JetBrainsRuntime\memory\`
(`MEMORY.md`, `naming-and-doc-style.md`, `skia-rendering-ledger.md`) — copy them into the guest session's own memory
directory for its project path and fix the ledger path in the pointer to the shared-folder location.

## State at hand-over

- Branches (signed, pushed to the fork `github.com/manu-unter/JetBrainsRuntime`, no write access to `JetBrains/JetBrainsRuntime`):
  - `manuel.unterhofer/JBR-10654-skia-backend` on `main` (JDK 28 line), tip `7d534d7de2ef`, 13 commits.
  - `manuel.unterhofer/JBR-10654-skia-backend-jbr25` on `jbr-release-25.0.4b508.27`, tip `34ecb6617283`, same 13 commits by cherry-pick. Use this one for anything that has to run IntelliJ; `main` cannot run the IDE (no `sun.awt.AppContext`).
- Native backend is split: `src/java.desktop/share/native/libawt_skia/` is portable C++ against Graphite; the platform
  contract is `SKPlatform.h`; the macOS half is `src/java.desktop/macosx/native/libawt_skia/MTLSK{Platform,Environment}.mm`
  + `MTLSKInterop.h` and is the template for Windows. Make rules: `make/modules/java.desktop/lib/AwtLibraries.gmk`,
  top-level `ifeq ($(SKIA_ENABLED), true)` block with per-OS additions. Configure: `make/autoconf/lib-skia.m4`
  (`--with-skia=<dir>`, adds `-DSK_DAWN` on Windows), pin `jb/project/tools/common/skia_prebuilt.conf`
  (`windows_arm64` and `windows_x64` digests are pinned), fetch `jb/project/tools/common/scripts/fetch_skia.sh`.
- The Java side is still Metal-bound and lives in `src/java.desktop/macosx/classes/sun/java2d/skia/` (`SKSurfaceData`
  wraps `MTLSurfaceData`; `MTLGraphicsConfig.getContext()` hands out the Skia context; `MacOSFlags` forces Metal when
  Skia is on; `LWCToolkit.sync()` drives the Skia queue). `SKEnvironment.java` is in `share/classes`. A Windows port needs
  the Java host-surface seam as well — see "Order of work".

## What the VM is for (WP 2.2, ledger §4 Phase 2)

Parallels 26.4 on Apple Silicon exposes Direct3D 11 to the guest and no Vulkan. That makes the VM the "VM / no Vulkan"
row of the coverage matrix: the JBR Vulkan backend has nothing here, Graphite + Dawn on D3D11 is the only accelerated
route. Check `dxdiag` for D3D12 too (Dawn prefers D3D12 where available). Record initializes? / frame time / correctness
/ pipeline-compile startup cost, as §4 asks.

## Order of work

1. **Toolchain, before any Skia work.** Windows ARM64 JBR build: Visual Studio 2022 Build Tools with the ARM64 toolset
   (MSVC + Windows SDK), Cygwin (JBR's documented build environment), boot JDK 25 for Windows ARM64 (a JBR 25 build is
   fine). Clone the fork, check out the jbr25 branch, build once **without** `--with-skia` to prove the toolchain
   (`bash configure --with-boot-jdk=…` then `make images`). Budget 1–2 h of VM time.
2. **Prebuilt + configure.** `jb/project/tools/common/scripts/fetch_skia.sh windows_arm64` (or `windows_x64` on an x64
   guest), then `configure … --with-skia=<unpacked dir>`. `lib-skia.m4` expects `out/<BuildType>-<os>-<arch>/*.a`;
   on Windows the prebuilt ships `.lib` files — the first fix is probably there (library extension and MSVC link
   flags). The m4 currently defines `-DSK_DAWN`; the prebuilt is built with Dawn (`dawn_enable_*`), headers under
   `include/gpu/graphite/dawn/` and `third_party/dawn/include` (ledger §11.1).
3. **Native Windows half** (`src/java.desktop/windows/native/libawt_skia/`, names `Win32SK*` / `DawnSK*` by the §10
   naming rule — decide once, platform prefix first):
   - `DawnSKPlatform.cpp` implementing `SKPlatform.h`: create a `wgpu::Instance`/adapter/device for D3D11 (D3D12 if
     present), `skgpu::graphite::DawnBackendContext` → `ContextFactory::MakeDawn`; `SKPlatform_BackendTexture` wraps the
     window's swapchain texture (`BackendTextures::MakeDawn`), colour type BGRA8; `SKPlatform_RequestRedraw` presents
     the swapchain; `SKPlatformScope` is a no-op.
   - `DawnSKEnvironment.cpp`: `initNative` (adapter description string) and `gpuApiNative` returning `"dawn"`;
     `SKEnvironment.Configuration.defaultsFor("dawn")` already has a slot (`SKEnvironment.java`).
   - Export list: Windows exports JNI only by default (`JNIEXPORT`), nothing to add. Warnings: `DISABLED_WARNINGS_microsoft`
     in the gmk block as needed.
4. **Java host-surface seam.** On macOS the Skia surface wraps a Metal surface that owns texture and layer. On Windows
   there is no comparable GPU pipeline to wrap in the IDE's configuration (D3D9 is off, Vulkan absent in the VM), so the
   Skia surface must own its presentation: a `WindowsSK*` graphics config / surface data that creates the Dawn swapchain
   for the `HWND` (the Win32 peer's window handle is reachable like `WGLSurfaceData`/`D3DSurfaceData` reach it) and
   registers Skia's `SKSurfaceData` as the window surface. Model it on how `Win32VKGraphicsConfig` /
   `Win32VKWindowSurfaceData` plug the Vulkan backend into the Windows toolkit, and on `WGLGraphicsConfig` for the
   HWND plumbing. This is the largest piece; do it after 1–3 compile.
5. **Verify** with the smoke test (`build/skia-smoke/SkiaSmoke.java`, compile it with the image's own `javac`) and the
   matrix (`RenderMatrix software|metal|skia` + `MatrixDiff`; on Windows the comparison is software vs. Skia only,
   add `--add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED
   --add-exports java.desktop/sun.awt.image=ALL-UNNAMED`, run at `-Dsun.java2d.uiScale=1`).

## Measurement tooling (for later, when the IDE runs on it)

`build/skia-bench/ijperf/` on the Mac: `run-night.sh` (arms × scenes), `collect.sh`, `compare.py`, `jfr-summary.sh`,
`sample-mem.sh`; the ultimate side (`RenderingPipelineArmListener`, `RenderingScenesPerformanceTest`,
`GeometryScenesPerformanceTest`) is on the local ultimate branch `manuel.unterhofer/skia-perf-arms` (not pushed). The
arm listener keys on `sun.java2d.metal`; a Windows arm would set `sun.java2d.skia=true` only.

## Pitfalls already paid for (ledger §12 has the details)

- Same-second checkout + build leaves stale objects: `touch` the libawt_skia sources before `make`; `CONF_CHECK=ignore`
  after branch switches.
- Two source files with the same stem in `share/` and the OS directory collide on one object file; make keeps one
  silently.
- The matrix runner and smoke class must be compiled with the image's own `javac` (class file version).
- zsh does not word-split `$VAR` in command position; `git cherry-pick` has no `-q`.
- Starter/IDE runs need network for TeamCity and JDK lists; a local `*SNAPSHOT*` installer copy avoids TeamCity.
