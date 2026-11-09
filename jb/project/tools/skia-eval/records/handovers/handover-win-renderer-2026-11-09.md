# Hand-over to the Windows machines: build and test the renderer branch (2026-11-09)

The first package — the Skia renderer behind a flag — is being prepared on
`manuel.unterhofer/JBR-10654-skia-renderer` (on `origin` and the fork; from `origin/main`
`dc4325491a09`). Read `records/plans/skia-renderer-mvp-plan-2026-11-09.md` first: §2 the line (what is in
and out), §7 the strategy, §8 the progress. The branch builds and passes its tests on macOS; its Windows
half was extracted blind and needs a Windows build.

**Do, on the VM (Dawn) and on the x64 machine (Dawn and Vulkan):**

1. Check out the renderer branch into its own worktree (do not mix with the evaluation branch's build), configure
   with `--with-skia=<the pinned prebuilt>` as the evaluation build does (`WITH_SKIA=1` in `mkimages_*.sh`, or the
   configure line of your evaluation build with a new `--with-conf-name`), build `images`.
   - Expect compile errors in `src/java.desktop/windows/native/libawt_skia/`: `Win32SKText.cpp` and
     `VKSKGaneshBench.cpp` are gone, `SKPlatform.h` lost the text hooks and gained `bool SKPlatform_LcdOrderBGR()`
     (implemented at the end of `Win32SKPlatform.cpp`), `SKBackend.h` lost the text-path and bench declarations.
     Fix what the compiler names, in the smallest way; keep the strategy of §7 (nothing new around the backend).
2. Run the package's tests: `make run-test TEST="jtreg:test/jdk/jb/java/awt/Graphics2D"` on the renderer
   configuration (25 tests; the Windows-only ones: `SkiaDawnBackendTest`, `SkiaDawnFeaturesTest`,
   `SkiaGraphiteApiTest`, `SkiaDeviceLossRecoveryTest`, `SkiaLcdModeDefaultTest`'s Windows lines, the LCD text
   tests). Then the standard `jdk_desktop` group's Java2D parts with `-Dsun.java2d.skia=true`
   (`JTREG="VM_OPTIONS=-Dsun.java2d.skia=true"`), as the evaluation's sweeps did.
3. Run IDEA on the renderer image with `-Dsun.java2d.skia=true -Dsun.java2d.skia.verbose=true` (the eval kit's
   `capture/run-ide-win.sh` with `JDK=` pointing at the renderer image) for a working session: default Dawn, then
   `-Dsun.java2d.skia.graphiteApi=vulkan`. Report crashes, hangs, visible defects; `hs_err` files and the IDE log.
4. Commit fixes on `manuel.unterhofer/JBR-10654-skia-renderer-<machine>N` on the fork (never push to the renderer
   branch itself), each with its red test where a test is possible; a raw record under `records/raw/`.

**Do not** bring anything of the evaluation branch's text stack, raster surface, hand-off, no-back-buffer path or
diagnostics into the renderer branch: the plan's line (§2, §7) is the user's decision.
