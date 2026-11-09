# Hand-over to the x64 machine: the first Skia package — the renderer on Windows, behind a flag (2026-11-09)

You own the renderer branch from here: `manuel.unterhofer/JBR-10654-skia-renderer` on `origin` and the fork, cut from
`origin/main` `dc4325491a09`, eight commits. Read first: `records/plans/skia-renderer-mvp-plan-2026-11-09.md` — §2 the
line (in / out), §3 the gaps, §4 the plan and the verification bar, §7 the strategy and the user's three corrections,
§9 the Windows-only decision; then the ledger's 2026-11-09 entries. The user's rules apply as everywhere: every
finding becomes a jtreg test that is red before the fix and green after, folded into the guarding commit; JBR-style
commit messages without trailers; n ≥ 5 interleaved measurements with median and spread; raw records under
`records/raw/raw-x64hw-<date>.md`; work on `manuel.unterhofer/JBR-10654-skia-renderer-x64hwN` on the fork and tell
the Mac what to integrate (or ask the user to move integration to you).

**The strategy in one sentence (user):** as little risky change around the new backend as possible without
invalidating it; within the backend, a complete package that is production-ready and performant on its own,
without needless complexity. Stability and reach, not performance: the acceleration structures inside the backend
stay (atlases, per-kind batching, the pipeline cache); nothing outside the backend changes for speed (Swing's back
buffer stays, no hand-off, no per-cycle present). Dawn/D3D11 is the default, Vulkan the alternative, as Chrome ships.

**What the branch is.** The evaluation tip's backend without the text stack (Skia's scaler, text path, shaper,
paragraph engine, decorations, luminance classes), the raster surface, the bench and profiling code, the hand-off and
the no-back-buffer path, the comparison knobs and the retired spellings; macOS removed. Kept switches: `sun.java2d.skia`,
`verbose`, `pipelineCache`, `shapes=analytic|marlin`, `graphiteApi=dawn|vulkan`, `imageAtlas=false`,
`lcd=blend|twopass|accumulate` (tests and triage), `dawn.backend=d3d12` (triage). Shared-code footprint:
`Win32GraphicsDevice`, `ScreenUpdateManager`, `WToolkit` (the queue sync only), four make files. The Windows native
half was extracted blind on the Mac: `Win32SKText.cpp` and `VKSKGaneshBench.cpp` are gone, `SKPlatform.h` lost the text
hooks and gained `bool SKPlatform_LcdOrderBGR()` (implemented at the end of `Win32SKPlatform.cpp`), `SKBackend.h` lost
the text-path and bench declarations, `SKBackend.cpp` carries its own `SUBPIXEL_TIE_NUDGE` (the shared tie rule is a
separate PR). The Mac built the branch's Java and shared native code before the macOS removal (22 tests green there)
and is verifying the build without `--with-skia`.

**First (Phase 1's close, yours):**
1. Worktree, configure with `--with-skia=<the pinned prebuilt>` (your evaluation build's line, a new conf name), `make
   images`; fix what the compiler names in the smallest way. Then the package's tests
   (`make run-test TEST="jtreg:test/jdk/jb/java/awt/Graphics2D"`, 25 tests), the Java2D parts of `jdk_desktop` with
   `JTREG="VM_OPTIONS=-Dsun.java2d.skia=true"`, and an IDEA session on the image with `-Dsun.java2d.skia=true
   -Dsun.java2d.skia.verbose=true`, Dawn then `graphiteApi=vulkan`.
2. Fold the branch into its reviewable series (the Mac could not rewrite history: build, native core, Java pipeline,
   Windows half, tests; the LCD-switch and macOS-removal commits folded into their areas) — your machine may rewrite
   the renderer branch's history until the review starts; say so in the record.
3. Item B9: the link list without Skia's image codecs if the backend does not need them (the library is 3.7 MB on
   macOS without the text modules; core + Graphite alone was 1.3 MB).

**Then Phases 2–4 of the plan (§3's items, Windows):** A1 the fallback ladder Dawn → Vulkan → JBR's D3D (today a
failed Skia start lands on GDI because `WindowsFlags` turns D3D off before Skia initializes); A2 multi-monitor (every
`Win32GraphicsDevice` on Skia, or none); A3 device loss (Dawn's device-lost callback recreates or falls back; the D3D9
protocol is the model); A4 the flusher crash (`hs_err_pid11528.log` in the VM's inbox of 2026-11-03: `awt_skia.dll+0x8e7258`
reading past a page in `SKRenderQueue.flushBuffer`; symbolize, run under minidumps); A5 the XOR hang if it reproduces on
Windows; A6 the global render state `g` versus per-context state; B7 the GPU budget measured on IDE scenes; B8 the
pipeline cache's home and bounds; C10 legal notices for everything linked; C11 CI (`WITH_SKIA=1` builds and the
`jbr_skia` group); C12 the prebuilt's provenance page; **C13b the GPU blocklist (part of the package, user):**
Chromium's GPU blocklist and WebGPU adapter blocklist vendored at a pinned revision with a converter, a matcher on
the adapter info before the device is created, three outcomes per rung, `sun.java2d.skia.gpuBlocklist=<file>|ignore`,
`verbose` naming the entry, tests with synthetic adapter info; D13 every test skips cleanly without Skia; D14 the
three-way matrix as a jtreg test; E16–E19 the review quality (knobs and instrumentation out, `SKBackend.cpp` split by
concern, the README for reviewers with the known differences); F20–21 the defaults; G22 the IDE wiring note; G23 the
hardware coverage list. The verification bar is §4's.

**Also, separately (after the package):** the Metal fixes as their own PRs (the glyph atlas eviction fix first) — the
Mac's job; the shared sub-pixel tie rule as its own PR.

## Addendum (Mac, 2026-11-09 late afternoon): texture paints

The evaluation branch found and fixed what your `SkiaTexturePaintTest` covers (the black fill in the flush that
caches the texture: `1ea0f81bdb4d`, the blit's linked image), and one thing the renderer branch still has: its
`setTexturePaintShader` moves every texture paint by half a texel (`preTranslate(-0.5f, -0.5f)`), which is right
when filtering and for integer mappings, but nearest under a fractional mapping then takes texel `floor(u + 0.5)`
where `TexturePaintContext` takes `floor(u)`. Measured on the Mac with your rule switched in: nearest at a 2.5×
anchor 10,872 of 13,056 pixels off the loops (with the evaluation branch's rule 231, all on the loops' own 32-px
tile seams). The rule to take is `SKBackend_TexturePaintSampling` in `50674e82145d`: the half texel only when
filtering; for nearest a 1/1024-texel bias per axis towards the side the context's truncated fixed-point steps
take (whole steps: the texel above an exact tie; fractional: the one below), derived from the plane equation.
Its test cases (bilinear 2.5×, nearest 1× and 2×, the 2× one allowing one row and one column in 32) are in the
evaluation branch's `SkiaTexturePaintTest` (mac | windows) — same name as yours; fold the nearest-2× case into
yours, red before on the renderer branch by the numbers above.
