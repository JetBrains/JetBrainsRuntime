# The first Skia package: the renderer behind a flag — state, the line, the gaps, the plan (2026-11-09)

Goal (user): make the first Skia package ready to be merged into JBR and shipped in IDEA behind a flag — the Skia
*renderer* in its minimum viable form, to keep the review burden manageable, in the most production-ready shape the
package can have. Everything below is measured against that goal; the evaluation branch keeps going separately.

## 1. Where the project stands (inventory of 2026-11-09)

**The branch** `manuel.unterhofer/JBR-10654-skia-backend` carries 246 commits since its fork point (`beee138cfe19`,
2026-10-21): 122 backend commits (112 "Skia", 5 "Metal", 3 "Windows", 1 "macOS", 1 raster), 120 evaluation-kit
commits, 4 others. The twin on the JBR 25 release line (`…-skia-backend-jbr25`) stopped in mid-October.

**Footprint.** Backend code in its own directories ≈ 15,800 lines: native shared 7,944 (`SKBackend.cpp` 3,078,
`SKRenderQueue.cpp` 684, `SKRaster.cpp` 668, `SKFontScaler.cpp` 707, `SKGlyphCache.cpp` 343, `SKLuminanceTypeface.cpp`
286, `SKStats` 318, …), macOS native 487, Windows native 1,393, Java `sun.java2d.skia` 4,687 (21 classes), platform Java
797, new `sun.font` classes 772 (`SKFontScaler`, `SKFontStrike`, `SKDrawableStrike`, `TextDecorations`). **Existing JDK
files changed: 51, +1,016 / −135** — macOS Metal native +301/−84 (glyph caches, text renderer, measurement switches),
`sun.font` +320/−26 (the Skia scaler's hooks, luminance classes, decorations), `java2d` core +75/−8
(`SunGraphics2D` +54: the raster pipes and dirty tracking), Windows Java +32/−5, make +89, build scripts +67. The eval
kit is 364 files / 23,206 lines; the tests 62 files / 7,335 lines (55 mention the flag; 38 are platform-guarded).

**Flags.** 1 product switch (`sun.java2d.skia`), 1 deployment switch (`skia.pipelineCache`), 3 tuning knobs (strike
cache, glyph atlas, glyph cache size), 16 evaluation knobs (shapes, strokes, msaa, blitQuads, lcd, present, submit,
raster, seven `text.*` dimensions, lcdOrder), 6 retired spellings, plus luminance classes, `strokeBend`,
`dawnBackend`, `font.scaler=skia`, `font.lcdGlyphs`, three Metal options, five `J2D_SKIA_*` environment variables.

**Build and dependency.** Optional at build time (`--with-skia=<dir>`, `SKIA_ENABLED` conditionals), off at runtime
without the flag; the prebuilt is pinned (`skia_prebuilt.conf`: JetBrains/skia release `m156-24300fbbcb`, SHA-256 per
platform) and fetched by `fetch_skia.sh` from the mac and Windows `mkimages` scripts when `WITH_SKIA=1`. Linux: no
platform half (configure stops), pins exist. `libawt_skia.dylib` is 9.6 MB *with* the text stack (skparagraph,
skshaper, skunicode, ICU); it was 1.3 MB with core + Graphite only (WP 1.3). No CI job builds with Skia. No legal
notices for Skia, ICU, Dawn, libwebp, wuffs, expat, piex, dng_sdk, skcms. No IDE wiring in this repository.

**Quality evidence.** Five independent review rounds (2026-10-14 … 10-20) with every macOS-fixable finding applied;
the 1,152-cell three-way matrix within 32 levels of the software loops (analytic 814 / marlin 844 / skia 794 cells
exact); the headless Java2D jtreg suites with the raster flag (287/292 …); RenderPerfTest 1.46× over Metal (macOS),
1.03× over GDI on the Windows VM; IDEA 2026.2/2026.3 run on both; the Windows VM's whole Skia jtreg set 47 passed.

**Known defects and risks on the backend proper** (the ledger's §13.2–13.5, the VM records): the render-queue flusher
crash on the VM (`awt_skia.dll` read fault in `SKRenderQueue.flushBuffer`, once, unreproduced); the RenderPerfTest hang
in analytic mode on `RotatedBox_XOR`; a failed Skia start on Windows lands on GDI (D3D is switched off before Skia
initializes); only the default screen is on Skia on Windows, other monitors GDI; native render state is one global `g`
reconciled from Java (per-context state as Metal's is a "later"); presentation not serialised with the queue
(review R2), cross-context sources on Windows (R7); device loss on Windows only logged (a recovery test exists for the
window surviving); GPU memory ≈ 280 MB above Metal on RenderPerfTest (Graphite budget 256 MB + atlases), the
`accumulate` LCD scratch never released; Dawn pipeline compilation ≈ 0.6 s at first use (the persistent cache exists,
`~/.java/skia/<ver>`); unbounded proxy/typeface caches in the text stack; real Windows hardware (NVIDIA, AMD, Intel,
Snapdragon), RDP and Hyper-V unmeasured.

## 2. The line: what the first package is, and what stays out

**In — "the Skia renderer": a Java2D pipeline that draws what today's Metal and D3D pipelines draw, behind
`-Dsun.java2d.skia=true`, on macOS (Graphite on Metal) and Windows (Graphite on Dawn, D3D11 first).**

- Surfaces and presentation: window surfaces through `MTLLayer` on macOS and Dawn's swap chain on Windows, volatile
  images, the surface manager, screen update manager; the software fallback for images that cannot be accelerated.
- Pipes: fills and draws with analytic antialiased shapes as the default and Marlin masks for general paths (the
  hybrid of variant 2), Skia's stroker (decided 2026-10-22), blits and transformed images (mesh quads), paints
  (colour, gradients, textures where accelerated), Porter-Duff composites, XOR, clips, mask fills and mask blits.
- Text: Java's glyph images (today's scalers: CoreText through `CStrike`, FreeType / DirectWrite on Windows) drawn
  through the pipe's glyph cache; grayscale on macOS, LCD on Windows with one compositing mode per platform.
- The render queue and the flusher thread, the pipeline cache directory, the Dawn backend selection.
- A GPU blocklist (user, 2026-11-09: part of the package — a core value inherited from Google through Skia and
  Chrome's field experience): Chromium's GPU blocklist and WebGPU adapter blocklist vendored at a pinned revision, a
  matcher on the adapter Dawn and Vulkan report before the device is created, three outcomes per rung of the Windows
  ladder (use, next rung, no Skia), an override switch, `verbose` naming the matching entry. §7, item C13b.
- Build: `--with-skia`, the pin and fetch script, the `mkimages` hooks, the legal notices; Linux configures as
  "no Skia" without an error.

**Out — kept on the evaluation branch for the later text-quality rounds:**

- The whole text stack: the Skia scaler (`sun.java2d.font.scaler=skia`, `SKFontScaler`, `SKFontStrike`,
  `SKDrawableStrike`, `SKLuminanceTypeface`), Skia's text path, shaper and paragraph engine, the text settings
  (`text.*`), luminance classes, system text decorations (`TextDecorations`, the `Decoration` / `Font2D` /
  `StrikeMetrics` changes), `font.lcdGlyphs`, the `FontStrikeDesc.luminance` plumbing. With it the dylib loses
  skparagraph, skshaper, skunicode and ICU (9.6 MB → about 1.3 MB).
- The Skia raster surface (`sun.java2d.skia.raster`, `SKRaster.cpp`, the `SKRaster*` pipes, the `SunGraphics2D` dirty
  tracking and `BufImgSurfaceData` hooks).
- The comparison knobs and instrumentation: `shapes=skia`, `strokes=java`, `msaa`, `blitQuads=edgeAA`, `present`,
  `submit`, the alternative LCD modes, `strokeBend`, `SKStats`, the `J2D_SKIA_*` environment variables, the retired
  spellings, the "EXPERIMENT" paths.
- The Metal changes: the glyph-cache size buckets and 2048-texel caches, the measurement switches, `textGammaCorrection`
  (JBR-10667) — each its own PR against Metal, not part of the Skia package. The glyph atlas eviction fix
  (`MTLTextRenderer`, 2026-11-02) is a bug fix for Metal and goes first, separately.
- The evaluation kit (`jb/project/tools/skia-eval`), the records, the catalog tooling.

**Kept as supported switches (documented, few):** `sun.java2d.skia` (on/off), `sun.java2d.skia.verbose`,
`sun.java2d.skia.pipelineCache` (directory or off), `sun.java2d.skia.shapes=analytic|marlin` (the one triage switch:
Marlin masks are the proven fallback for a rendering difference report), `sun.java2d.skia.dawnBackend=d3d11|d3d12`
(Windows triage). Everything else becomes a fixed default.

**Why this line.** The renderer is what the IDE needs first — Windows is fully software today and macOS gains the
analytic shapes — and its correctness evidence is the strongest (the matrix, the suites, five reviews). Every text
change touches shared `sun.font` code that every platform's text runs through, carries metric and layout risk, and is
still being worked; it needs its own review package later. The raster surface is a performance experiment with an
open accuracy question (strokers). The knobs exist to compare arms; a shipped renderer compares nothing.

## 3. Gaps to fill before the review (what "production-ready" asks for)

Ordered by what blocks a merge; sizes are working days for one engineer with the VM for the Windows half.

**A. Failure and recovery paths (blocking)**
1. Windows: a failed Skia start must land on D3D, not GDI — decide Skia's availability before `WindowsFlags` turns D3D
   off, or re-enable D3D on failure. Test: a forced init failure (`dawnBackend=none` or an injected error) renders
   through D3D. 1 d.
2. Windows multi-monitor: every `Win32GraphicsDevice` on Skia, or none (one adapter per device; cross-adapter sources
   R7). Decide, implement, test on the VM with a second virtual display. 2–3 d.
3. Device loss on Windows: Dawn's device-lost callback must recreate the device and surfaces or fall back to D3D (the
   D3D9 lost/restore protocol is the model). The existing recovery test is the start. 2–3 d, VM.
4. The flusher crash (`hs_err_pid11528`): symbolize `awt_skia.dll+0x8e7258` against the VM's build, audit the text
   record reads near a buffer's end (`SKRenderQueue.cpp`), run the arm under `-XX:+CreateMinidumpOnCrash`. 1–2 d.
5. The analytic-mode XOR hang (`RotatedBox_XOR`): reproduce on the XOR tests alone, fix or route XOR through the
   Marlin path. 1–2 d.
6. Native render state: either per-`SKContext` state (as Metal) or a documented argument why the global `g` with
   Java-side invalidation is sound under multiple displays — review R2 (presentation serialised with the queue) and
   the unlocked `pTexture`/`layer` reads belong here. 2–4 d.

**B. Resources and startup (blocking for "behind a flag", measured not guessed)**
7. GPU memory budget: set Graphite's `fGpuBudgetInBytes` and the atlas sizes for an IDE, measure RenderPerfTest and
   an IDE session against Metal (today ≈ 280 MB above). 1–2 d.
8. The pipeline cache: directory under the JBR's convention (not `~/.java/skia` unless agreed), versioned by the Skia
   tag and driver, bounded, cleaned; first-present timing with and without on the VM (≈ 0.6 s today). 1 d.
9. `libawt_skia` without the text modules: re-link, confirm ≈ 1.3 MB, confirm the export list, dead-stripping, `-MT`
   on Windows. 0.5 d.

**C. Build, CI, legal (blocking)**
10. Legal notices: `src/java.desktop/share/legal/skia.md` (BSD-3) and notices for ICU, Dawn, skcms, libwebp, wuffs,
    piex, dng_sdk, expat — whatever stays linked after item 9 — into `legal/java.desktop/`. 0.5 d.
11. CI: a build configuration per platform with `WITH_SKIA=1` (TeamCity through the `jb` CLI, or the GitHub
    workflows), the non-Skia build verified, Linux configure a no-op for the flag; the `jbr_skia` jtreg group
    running the Skia tests with the flag on, and the standard `jdk_desktop` group with the flag on as a nightly.
    1–2 d + the CI owners.
12. The prebuilt's provenance: the JetBrains/skia release tag, the build script, who rebuilds it, how the pin is
    bumped (a page in `jb/project/tools/common`). 0.5 d.

**D. Tests (blocking)**
13. Every Skia test skips cleanly without Skia (`@requires` or `jtreg.SkippedException`); today 9 fail on a non-Skia
    build. The MVP subset: the pipeline, shape, gradient, XOR, translucent, LCD, glyph-cache and device-loss tests;
    the text-stack and raster tests stay on the evaluation branch. 1 d.
14. The three-way matrix as a jtreg test (Skia vs the software loops within the agreed thresholds, per cell group),
    nightly on both platforms. 1–2 d.
15. The RenderPerfTest arms as a documented benchmark procedure (not a test). 0.5 d.

**E. Code quality for the review (blocking for the burden, not for function)**
16. Strip the evaluation knobs and instrumentation listed in §2, the retired spellings, the "EXPERIMENT" paths;
    `SKStats` reduced to what tests and `verbose` need. 1–2 d.
17. `SKBackend.cpp` (3,078 lines) split by concern — surfaces, shapes, images, text, state — so a reviewer can read
    one file per topic; the naming rule (`g`, `cc`) resolved by item 6. 1–2 d.
18. The cleanup-review items marked "later" in §13.2 re-checked against the MVP code (b, c, d); the JNI 14-slot rule
    and the kN32 rule written where they apply. 0.5 d.
19. An architecture note for reviewers (`src/java.desktop/share/native/libawt_skia/README.md`): the queue, the
    flusher, the contexts, the glyph cache, the hybrid shape rule, the platform halves, the known differences to
    Metal and D3D with their sizes (the matrix's residues, gradient dithering ±1–4 levels, 1-px stroke joins). 1 d.

**F. Defaults to settle (decisions, mostly taken)**
20. Shapes: analytic default, Marlin fallback (taken). Strokes: Skia's (taken). LCD mode per platform (Windows
    twopass by the test; confirm with the VM's LCD findings). Blit quads: mesh. MSAA: off. Present and submit: flush.
21. The fidelity residues accepted for the package: listed in the README with numbers; the triage with design is the
    later text-quality round, not this package.

**G. Outside this repository**
22. IDE wiring: the flag in `idea.properties` or a registry key, off by default; a Help › Diagnostic indication
    (`verbose`); the IDE's `sun.java2d.metal` / `d3d` handling when Skia is requested. A note for the IDE team.
23. Windows hardware coverage: untested on real GPUs. The flag-off package may ship without it; turning it on for
    users needs the WP 2.2 matrix (NVIDIA, AMD, Intel Xe, Snapdragon X, RDP, Hyper-V). A smoke list for the release
    team, and a blocklist / fallback story before any default change.

**Not gaps for this package:** Linux (no platform half; the flag is a no-op), Vulkan (no Skia backend for it), text
fidelity against the platform (the evaluation's stream), the raster surface, performance beyond parity (1.46× / 1.03×
measured).

## 4. The plan: a review branch built from the evaluation branch

**Target.** `manuel.unterhofer/JBR-10654-skia-renderer` on `origin` from `origin/main`'s tip (the JBR 26 line), with
a backport branch on the JBR 25 release line once the review is through — IDEA ships on JBR 25; the user decides
which line the first package targets (a package on `jbr25` first means the backport is the review, and `main` follows).

**Shape.** Not the evaluation history: a curated series of about ten commits, each one reviewable alone, each
building and passing its tests on both platforms:

1. Build and dependency: `lib-skia.m4`, the pin, `fetch_skia.sh`, the `mkimages` hooks, the make rules, the legal
   notices; configure on Linux says "no Skia" and builds.
2. The render queue and the native core: `SKRenderQueue`, `SKBackend` split by concern, `SKEnvironment`, the glyph
   and mask caches, the quad batches, the pipeline cache — no text stack, no raster, no stats beyond the counters tests
   read.
3. The Java pipeline: `sun.java2d.skia` without the raster and text-route classes; the hooks in `SunGraphics2D`,
   `SurfaceData`, `ValidatePipe` reduced to what the pipeline needs.
4. macOS: `MTLSKEnvironment`, `MTLSKPlatform`, `MTLSKSurfaceData`, the `MTLGraphicsConfig` / `MTLLayer` / `MTLContext`
   hooks, `MacOSFlags`.
5. Windows: Dawn environment and platform, `Win32SK*`, `WindowsFlags`, `ScreenUpdateManager`, `Win32GraphicsDevice`,
   `WToolkit`, the ICU-less copy rule.
6. Failure paths and resources: the items A1–A6 and B7–B8, each with its test.
7. Tests: the MVP subset, guarded; the matrix test; the `jbr_skia` group.
8. Documentation: the README for reviewers, the flag documentation, the known-differences list.
9. (Separately, before 1) Metal: the glyph atlas eviction fix as its own PR; the other Metal commits stay out.

**Method.** Each commit is assembled by extracting from the evaluation branch (`git diff` of the relevant paths at
the evaluation tip, trimmed by the §2 line), not by cherry-picking history; the evaluation branch keeps its history
and continues. A script in the kit records the extraction (which paths, which hunks dropped) so the two branches can be
re-synchronised when the evaluation fixes something the package also needs. The test-first rule holds: every gap of
§3 gets its red test on the review branch before its fix; the tests of the evaluation branch that cover the kept code
move over as they are.

**Order and effort.** Phase 0 (user decisions, half a day): the target line, Windows in or macOS first, the kept
switches, the pipeline cache location. Phase 1 (2–3 d): commits 1–5 as the skeleton, building and passing the kept
tests on both platforms (the VM builds and runs the Windows half). Phase 2 (5–8 d): §3 A and B — the failure paths,
device loss, the multi-monitor decision, the crash and the hang, the global state, the budgets. Phase 3 (3–4 d): §3 C,
D, E — CI, legal, test guarding, the matrix test, the split and the README. Phase 4 (2 d): the review package — the PR
description with the evidence (matrix, suites, benchmarks, reviews), the known differences, the IDE note; then the
backport. About four weeks of one engineer with the VM, with the Windows items the long pole; macOS first would save
Phase 2's Windows half (about a week) and ship the smaller gain.

**Verification bar for "ready for review":** both platforms build with and without `--with-skia`; `jbr_skia` green
on both and `jdk_desktop` with the flag on at the evaluation's pass rate or better; the matrix test green; the
RenderPerfTest arms at or above the recorded ratios; IDEA runs a working day on each platform with the flag on with
no crash, hang or leak visible in the GPU budget; a forced init failure, a device loss and a second monitor behave as
decided; the legal notices present; the dylib at its core size; no evaluation knob left; the README answers a
reviewer's first ten questions.

**What the evaluation branch does meanwhile.** It stays the place for the text-quality rounds and the raster surface;
after the package is cut, the evaluation branch rebases on top of it (the package becomes the baseline) so the text
stack is a delta on the shipped renderer rather than the other way round.

## 5. Decisions needed from the user before Phase 1

1. The target line: `main` (JBR 26) with a `jbr25` backport, or `jbr25` first.
2. Windows in the first package, or macOS first with Windows as the second package.
3. The kept switches (§2's list of five) — anything to add or drop.
4. The pipeline cache's home and the GPU budget target (an IDE default).
5. Whether the Metal fixes go as separate PRs first (recommended) or wait.

## 6. Addendum 2026-11-09 (evening): the x64 machine's round (`…-x64hw4`, merged as `0ce1b1e5ea4f`) against this plan

**Decisions taken by the user on §5:** the target is `main`, no backport; Windows is in the first package; the Metal
fixes go as separate PRs, prepared after the renderer branch; the kept switches to be re-evaluated (below).

**What the round adds (75 commits, 2026-10-28 … 11-09; ≈ 9,400 lines, half of them records and the IDE performance
harness `bench/ide-perf`):**

- *Graphite on native Vulkan on Windows* (`sun.java2d.skia.graphiteApi=vulkan|dawn`), behind a Windows platform layer
  independent of the graphics API (`Win32SKPlatform`, `VKSKPlatform`, `DawnSKPlatform`, the DirectWrite text code in
  `Win32SKText.cpp`); the Dawn device asks for the features Graphite uses; `dawnBackend` became `dawn.backend`.
  Measured on an RTX 3080 Ti: Skia/Vulkan 10.06 s EDT paint typing against the loops' 12.17 s and JBR Vulkan's 9.79 s;
  Skia/Dawn-D3D11 10.28 s; typing latency equal everywhere (p90 8–10 ms). Against JBR Vulkan Skia is behind in flush
  time (1.53× typing, 1.20× UI; like for like on text 1.23×) because Graphite records per draw (0.5–0.8 µs before any
  GPU command) and JBR Vulkan takes shortcuts (LCD drawn grayscale, AA outlines missing).
- *Presentation and Swing's buffer:* windows presented once per paint cycle on Windows (`present=cycle` the default
  there) and Swing painting straight into the window surface (`SunToolkit.isWindowSurfaceDoubleBuffered`,
  `RepaintManager`; `sun.java2d.skia.swingBackBuffer=true` restores the buffer); held recordings (a flush that
  presents nothing continues its recording). These touch `java.awt.Window`, `WindowPeer` (`updateWindowLater`),
  `AWTAccessor`, `SunToolkit`, `RepaintManager`, `RenderBuffer`, `BufferedSurfaceDataExt` — shared AWT and Swing code
  that the AWT owners must review; the asynchronous end-of-paint hand-off (`asyncPresent`, `commitLater`) stays off
  and parked.
- *Batching, after the user's cutoff of 2026-11-06:* only what any graphics developer expects of a Java2D backend on
  Skia — the atlases (glyphs, Marlin tiles, images: `sun.java2d.skia.imageAtlas`, default on), quad batching per
  kind with colours per vertex and a per-batch paint cache, the same-destination `SET_SURFACES` skip; every strategy
  that reordered Java2D's drawing (the lazy clip, kinds side by side, blits and shapes past the batches, the LCD
  colour batches) removed again — ≈ 780 lines out, the cutoff costing 15–17 % of the flush wait and no latency.
- *Text:* a text blob cache for Skia's text path (`text.blobCache`, default 16384 — the text path only, out with it);
  *diagnostics:* `profileFlusher*`, `benchGraphite`, `benchGanesh` (links `skia_ganesh_ext` on Windows for it),
  `J2D_SKIA_DAWN_FEATURES/TOGGLES`, `J2D_SKIA_RECORDING_PER_FLUSH`, more `J2D_SKIA_STATS` attribution; *JBR Vulkan
  fixes* found on the way (`WToolkit.sync()`, the default configuration), each its own PR like the Metal ones.
- *The x64's own judgment and route:* a Skia-based renderer, Graphite on the GPU — Metal on macOS, native Vulkan first
  on Windows with Dawn/D3D11 as the fallback — and gates before committing: seat telemetry, the all-arms series on an
  integrated GPU, an Intel laptop and a Snapdragon machine, a fidelity-fair comparison with JBR Vulkan, a soak of
  the new defaults in daily use, robustness (device loss, TDR, display and DPI changes, sleep, RDP).

**What it resolves or improves among §3's gaps.** B7 partly (fewer draws and recordings: held recordings, the image
atlas, one present per cycle); A1's neighbourhood (the platform layer now has a place for the fallback ladder
Vulkan → Dawn/D3D11 → D3D, and the Dawn device requests its features); D15 (the IDE performance harness is the
documented benchmark procedure beside RenderPerfTest); the Dawn-only question of §1 (native Vulkan measured on real
hardware for the first time, a second Windows GPU vendor still missing). It does not touch A2–A6 (multi-monitor,
device loss, the crash, the hang, the global state), C, D13–14, or E.

**New gaps it adds.** (a) The shared AWT/Swing changes for the no-back-buffer path: a review by the AWT owners,
the behaviour on every other pipeline unchanged by construction (`isWindowSurfaceDoubleBuffered` false elsewhere) but
to be tested (`SkiaSwingBackBufferTest` covers Skia; a non-Skia run of the Swing suites is the check); flicker in
daily use is the x64's own open soak item. (b) The Vulkan loader at run time: `graphiteApi=vulkan` needs the loader
present and a driver; the fallback to Dawn when it is not, and to D3D when neither starts, is item A1 widened to a
ladder. (c) `SkiaRobotHandOffTest` depends on what ran before it in the full set (constant failure in the run, passes
alone) — a test to fix or drop before the package. (d) `skia_ganesh_ext` linked for a diagnostic: out of the package's
link line. (e) The Vulkan platform half is a month old and VM-untested (the VM has no Vulkan); the Dawn half is the
one both Windows machines ran.

**The switches, re-evaluated.** Keep in the package: `sun.java2d.skia`, `verbose`, `pipelineCache`,
`shapes=analytic|marlin` (triage), `graphiteApi=vulkan|dawn` (the Windows API choice and the fallback's first rung —
replaces `dawn.backend` as the kept Windows switch; `dawn.backend=d3d12` stays only if D3D12 is to be a triage target),
`imageAtlas=false` (an off switch for a report of an image rendering difference: cheap, conventional),
`swingBackBuffer=true` (an off switch for the new Swing path: the one change that reaches Swing users). Out: everything
under `text.*` including `blobCache`, `raster`, `strokes`, `msaa`, `blitQuads`, `submit`, `present` (one default per
platform, the switch stays internal), `asyncPresent`, `profileFlusher*`, `benchGraphite`, `benchGanesh`, `strikeCache`
/ `glyphAtlas` / `glyphCache` (fixed defaults), the `J2D_SKIA_*` variables, the retired spellings.

**On §5's question 4 (the pipeline cache's home, the GPU budget).** The pipeline cache is Graphite's compiled shaders
and pipelines persisted between runs so the first frames do not pay the compilation (≈ 0.6 s on Dawn measured on the
VM): a directory of files keyed by the Skia tag and the driver, today `~/.java/skia/<version>` by default and
`sun.java2d.skia.pipelineCache=<dir>|off`. A shipped runtime should put it where JBR keeps per-user caches (the IDE's
`system/` directory when the IDE sets the property, else the user's cache directory per platform), bound its size,
and invalidate it on a driver or Skia change — a small decision with a user-visible effect on first start. The GPU
budget is Graphite's `fGpuBudgetInBytes` (256 MB default) plus the backend's own atlases: the measured footprint was
≈ 280 MB above Metal on RenderPerfTest; the decision is the target for an IDE (for example 128 MB + atlases) and the
measurement that it holds under the IDE scenes. Neither needs a product decision from you beyond "a cache directory
under the IDE's system directory is acceptable" and "a budget of about N MB"; I will propose numbers with
measurements in Phase 2.

## 7. Addendum 2026-11-09 (night): the strategy in one sentence, three corrections (user)

**The strategy.** *As little risky change around the new backend as possible without invalidating the backend
itself; within the backend, a complete package that is production-ready and performant on its own, without needless
complexity.* The first version evaluates stability and reach, not performance. The acceleration structures a
backend of this kind ships with — the atlases (glyphs, Marlin tiles, images), per-kind quad batching, the pipeline
cache — stay in, because they live entirely inside the backend. Anything that changes behaviour outside the backend to
make it faster stays out.

**Correction 1 — Swing's back buffer stays; the no-back-buffer path and its API leave the package.** The per-paint-
cycle present with Swing painting straight into the window surface is a performance optimization that touches
`java.awt.Window`, `WindowPeer`, `AWTAccessor`, `SunToolkit` and `RepaintManager` for every pipeline's benefit of
doubt and a flicker soak the x64 still lists as open. Out of the package: `SunToolkit.isWindowSurfaceDoubleBuffered`,
`Window.updateWindowLater`, the `RepaintManager` change, `sun.java2d.skia.swingBackBuffer`, the hand-off
(`asyncPresent`, `commitLater`), and with them the per-cycle present as the Windows default — the package presents
per flush as the evaluation did before 2026-11-03 (`SkiaPresentCycleTest`'s mode stays an internal option, not a
switch). The kept switches: `sun.java2d.skia`, `verbose`, `pipelineCache`, `shapes=analytic|marlin`,
`graphiteApi=dawn|vulkan`, `imageAtlas=false`, `gpuBlocklist=<file>|ignore` — seven. Shared-code touches of the package shrink to the pipeline hooks
(`MTLGraphicsConfig`, `MTLLayer`, `WindowsFlags`, `ScreenUpdateManager`, `Win32GraphicsDevice`, `SunGraphics2D`'s
pipe validation where the backend needs it).

**Correction 2 — Dawn is the default on Windows, Vulkan the fallback.** As Chrome ships (Graphite on Dawn/D3D11 by
default since Chromium 158, with its driver workarounds behind it), and because no stability or reliability numbers
exist for either route yet — the safe choice. The code already agrees: `Win32SKEnvironment.cpp` takes Vulkan only
when `graphiteApi=vulkan` is set; the x64's "Vulkan first" was its performance judgment, not the default. The
package's ladder on Windows: Graphite on Dawn/D3D11 → Graphite on Vulkan (when Dawn fails to start or the adapter is
blocklisted for Dawn) → JBR's D3D pipeline (when Skia fails altogether; item A1) → the loops. D3D12 under Dawn stays
reachable for triage (`dawn.backend=d3d12`) if at all, not in the ladder.

**Correction 3 — a GPU allow/blocklist, and what we inherit.** What comes with the Skia artifact: Dawn's own
driver-bug workarounds — toggles Dawn enables itself per backend and adapter from the vendor, device and driver it
sees (D3D11, D3D12 and Vulkan each have a set, grown from Chrome's field experience), and Graphite's caps-level
workarounds in its Dawn and Vulkan backends (`DawnCaps`, `VulkanCaps`). What does not: Chromium's GPU blocklist
(`gpu/config/software_rendering_list.json`: GPUs and drivers on which hardware acceleration is disabled altogether,
per OS, vendor, device, driver version range), its driver-bug list (`gpu_driver_bug_list.json`: workarounds Chromium
turns into Dawn toggles and other switches), and its WebGPU adapter blocklist — these live in Chromium, not in Skia or
Dawn, and are matched by Chromium's own GPU info collection. The platform layer already has the inputs: Dawn's
`wgpu::AdapterInfo` (vendor, device, driver description, backend) and the Vulkan physical device properties; Dawn
toggles are already passable (today an experiment variable, `J2D_SKIA_DAWN_TOGGLES`).

*Decision (user, 2026-11-09): part of the package — a core value inherited from Google through Skia.* The form: A stability-and-reach release
without a way to steer around a known-bad driver would have only the global flag as its lever, and Chrome's list is
the best field knowledge available. The minimal form, as item **C13b** of §3 (3–4 days): (1) a vendored list in the
JBR (`src/java.desktop/windows/classes/sun/java2d/skia/gpu-blocklist.json` or a resource) generated by a script from
Chromium's `software_rendering_list.json` (the entries whose features include all or 2D-canvas acceleration, for
Windows) and its WebGPU adapter blocklist, at a pinned Chromium revision recorded in the file, with a converter the
kit keeps; (2) a matcher at initialization — OS version, vendor and device id, driver version ranges in Chromium's
comparison semantics — applied to the adapter Dawn reports (and the Vulkan device) before the device is created,
with three outcomes per rung: use, skip to the next rung, no Skia; (3) `sun.java2d.skia.gpuBlocklist=<file>|ignore`
(the seventh kept switch, for support and for the inevitable false positive) and `verbose` naming the entry that
matched; (4) tests with synthetic adapter info. Later, not in the package: Chromium's driver-bug list mapped to Dawn
toggles entry by entry (each a judgment), and the IDE's own telemetry feeding a JBR-side list.

## 8. Progress

- **2026-11-09, Phase 1 (skeleton):** `manuel.unterhofer/JBR-10654-skia-renderer` on `origin` and the fork, from
  `origin/main` `dc4325491a09`, six commits (build, native core, Java pipeline, macOS, Windows, tests), building on
  macOS; `libawt_skia.dylib` 3.7 MB, 19 exports. Kept switches as §7 plus `dawn.backend=d3d12` as a Windows triage
  switch (the order Dawn tries). Tests on this build: 22 passed, 7 Windows-only skipped; `sun.java2d.skia.lcd` kept as a test and triage switch
  (seventh commit). Open for Phase 1's close: the Windows half compiled and its tests run on the VM or the x64
  machine (`handovers/handover-win-renderer-2026-11-09.md`; `Win32SKPlatform.cpp` gained `SKPlatform_LcdOrderBGR`,
  blind here); the link list without the image codecs (B9); the series folded (a history rewrite of the branch).

## 9. Decision 2026-11-09 (late): the first package is Windows-only, and the x64 machine owns it from here

The user: hand the renderer-branch work over entirely to the x64 machine, and remove macOS from the branch. So:

- **The package** is the Skia renderer on Windows (Graphite on Dawn/D3D11 by default, Vulkan as the alternative)
  behind `-Dsun.java2d.skia=true`, from `main`, no backport. The macOS half (Graphite on Metal, the Metal pipeline's
  hooks) stays on the evaluation branch for a later package; the renderer branch's configure accepts `--with-skia` on
  Windows only, its tests require Windows (`9e7d5d13a4dd`).
- **Ownership:** the x64 machine continues on `manuel.unterhofer/JBR-10654-skia-renderer` through Phases 2–4 of §4
  (its own branch names `…-skia-renderer-x64hwN` on the fork for its rounds; the Mac integrates onto the renderer
  branch as before, unless the user moves integration to the x64 as well). The hand-over:
  `handovers/handover-x64-renderer-2026-11-09.md`.
- **This Mac's remaining contribution to the package:** the build without `--with-skia` verified on macOS (item C1,
  the branch's Java classes under `sun.java2d.skia` compile on every platform), the records, and the integration.
- **§3 re-scoped to Windows:** A1 (the fallback ladder Dawn → Vulkan → JBR D3D), A2 (multi-monitor), A3 (device loss),
  A4 (the flusher crash), A5 (the XOR hang: reproduce on Windows, it was seen with RenderPerfTest on the Mac — if it is
  macOS-only it leaves the package's list), A6 (the global render state), B7–B9, C10–C13 and C13b (the blocklist), D13–15,
  E16–19, F20–21, G22–23 — all on the x64, with the VM for a second Windows machine (Dawn only).
