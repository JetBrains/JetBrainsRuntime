# Skia backend on real Windows x64 hardware: mission, plan and hand-over (2026-10-22)

This directory is the evaluation kit for the experimental Skia rendering backend of JBR
(`-Dsun.java2d.skia=true`, Graphite on Dawn/Direct3D 11 on Windows, Graphite on Metal on macOS;
JBR-10654). It was assembled on 2026-10-22 from the tools of the two machines that built and
measured the backend so far: a Parallels Windows 11 ARM64 VM (paravirtualised GPU, D3D11 only)
and an M1 Max Mac. **You are the third machine: Windows x64 with a discrete GPU.** Nothing here has
run on such hardware yet; the VM numbers are known to be distorted by the virtual GPU and the
Mac numbers come from a different graphics API.

Read this file first, then `notes/skia-rendering-ledger-snapshot-2026-10-22.md` sections 0
(status board), 10 (decisions), 13 (open items) and 19 (IDE performance plan). The ledger is the
project's memory; its master copy lives on the Mac, this is a snapshot.

> **2026-10-29 addendum — Skia as the software pipeline, to be verified on Windows.** Chunk 1 of the long tail is on the
> branch (`-Dsun.java2d.skia.raster=true`; `d166dd1447e2`). The round for it — the nine `SkiaRaster*Test`s with the LCD
> sub-pixel order check, the five JDK tests the Mac sweep flagged, the sweep on Windows, the metrics census, the bench
> and the IDE with the GPU off — is written up in `build/notes/handover-vm-2026-10-29.md` on the Mac and copied below the
> tool index as §11 of this file.

## 1. Mission

Measure and validate the Skia backend on real Windows x64 hardware, and bring back:

1. **Performance** of the Skia backend against the pipelines Windows users have today
   (Direct3D 9 default, the software loops that IDEA runs with `-Dsun.java2d.d3d=false`),
   micro (RenderPerfTest, text benches) and in the IDE.
2. **Pixel conformance** against the software loops on real display scales (100 / 125 / 150 /
   200 %), with the instruments that found the last four defects on the VM.
3. **The paths no machine could test:** GPU device loss (TDR) and recovery, more than one
   adapter (iGPU plus dGPU) and more than one display, pipeline-compile start-up cost on a real
   driver.
4. Every defect as a **jtreg test that is red before the fix and green after**, plus a dated raw
   record in the ledger's format.

Not in the mission: changing defaults or design (that is the user's call, on the numbers you
bring), text rendering on Skia beyond measuring the switches that exist, macOS.

## 2. Where things stand

- Branch: `manuel.unterhofer/JBR-10654-skia-backend` on `origin` (JetBrains/JetBrainsRuntime),
  the `main` line (JDK 28, boot JDK 27 or newer). This hand-over branch sits on its tip
  `5de299e4133`. The `-jbr25` line is frozen (tag `archive/skia-jbr25-2026-10-21` on the fork).
- Pipeline knobs, all system properties (details in `SKEnvironment.java` and the commit messages):

  | property | values | default | what it selects |
  |---|---|---|---|
  | `sun.java2d.skia` | `true` | off | the backend (on Windows, `d3d` is then off) |
  | `sun.java2d.skia.shapes` | `analytic` / `marlin` / `skia` | `analytic` | rrects, ovals and parallelograms analytically, Marlin masks, or Skia paths for everything |
  | `sun.java2d.skia.strokes` | `skia` / `java` | `skia` | who strokes: Skia's analytic stroker, or Java's stroker outline filled by Skia |
  | `sun.java2d.skia.lcd` | `blend` / `twopass` / `accumulate` | `accumulate` without framebuffer fetch (all of Windows), `blend` on Apple GPUs | LCD text compositing |
  | `sun.java2d.skia.msaa` | 1 / 2 / 4 / 8 | 1 | Graphite's internal sample count |
  | `sun.java2d.skia.blitQuads` | `mesh` / `edgeAA` | `mesh` | image blit route (measured equal; an A/B knob) |
  | `sun.java2d.font.scaler` | `skia` | off | glyph images and metrics from the Skia font scaler (DirectWrite host on Windows; new on 2026-10-22) |
  | `sun.java2d.skia.text.gray` | `coverage` / `smoothed` / `darkened` | `coverage` | grayscale look of the Skia scaler's glyphs |
  | `sun.java2d.skia.verbose` | `true` | off | prints `Skia rendering enabled: YES (Skia m156 Graphite/Dawn on <adapter> (D3D11), shapes: ..., msaa: ..., blitQuads: ..., lcd: ...)` |
  | `sun.java2d.uiScale` | number | OS scale | **set to 1 for every pixel tool** unless the scene is about the real scale |

- What the VM established on Dawn (ledger section 21 and the 2026-10-21 records), for comparison:
  - jtreg: every Skia run line under `test/jdk/jb/java/awt/Graphics2D` passes (22 lines on the
    main line); `GlyphAtlasEvictionThroughputTest` ratio 10.3 (gray) / 5.9 (LCD) against a
    Windows limit of 16 (Metal sits at 2.5; the limit is wide because of the VM's upload path;
    **your ratio is the first real data point**).
  - LCD text, 100 / 500 / 1000 overlapping lines, ms per frame: blend 5.5 / 20.7 / 57 (a
    destination copy per draw, about 85 us each on the VM), two-pass 4.1 / 13.7 / 29.6,
    accumulate 3.0 / 8.5 / 14.5, software loops 12.4 at 1000. 90 non-overlapping lines:
    5.4 / 3.7 / 3.0.
  - Rendering matrix (`RenderMatrix` plus `MatrixDiff`, 1152 cells): 974 identical / 178
    coverage-level / 0 suspect against Skia-on-Metal; about 800 within 32 levels of the software loops.
  - IDE scenes (IDEA 2026.3 EAP): text within 6 levels of the software loops on light and 9 on
    dark at 100 %; 0 pixels beyond 32 levels in the text scenes at 200 %; blend is pixel-identical
    apart from dots. The only rendering class left is the curved-edge coverage model (up to 20
    levels on arcs: Skia's distance coverage against Marlin's area coverage, both inherent).
  - Start-up: Dawn pipeline compilation about 0.6 s on the VM (`StartupCost`); a persistent
    pipeline cache is the candidate fix if real hardware shows the same.
  - Device loss: `DisplayChangeProbe` exercises display changes (new device, shared per adapter,
    cross-configuration blits); **a real TDR was never forced** (needs `dxcap -forcetdr`, an
    elevated shell, and a non-virtual GPU).
- What the Mac established on Metal: RenderPerfTest Skia/Metal geomean 1.04x (variant 1),
  analytic shapes 2.2 to 3.5x over Marlin tiles on AA rrects and ovals; Skia footprint about
  280 MB above Metal on RenderPerfTest (`ContextOptions::fGpuBudgetInBytes` is the knob to try);
  IDE scene timings within noise between Metal and Skia (ledger sections 11.16 and 11.17).
- Perf CI (TeamCity) exists for x64 Windows (`JBR_Dev_Main_WindowsX64nomod` builds with
  `WITH_SKIA=1`; Render jobs on `intellij-windows-hw-munit-730`) but its history is vsync-locked
  and its Robot mode hangs; your local numbers are the trustworthy ones for now.

## 3. Rules of engagement (from the user; not optional)

- **Branches.** Never push to or rewrite `manuel.unterhofer/JBR-10654-skia-backend` (the Mac owns,
  rewrites and signs it). Branch off its tip; push `fixup!` or topical commits on
  `manuel.unterhofer/JBR-10654-skia-backend-<machine>N` to the fork
  `github.com/manu-unter/JetBrainsRuntime` (add it as a remote if missing). Unsigned commits are
  fine. **No co-author trailers** in commit messages. After the Mac rewrites the shared branch,
  reset your clone to it; do not merge.
- **Tests before fixes.** Every finding gets a jtreg test under `test/jdk/jb/java/awt/Graphics2D`
  (text: `TextRender/`) that is red before the fix and green after, with run lines for `skia`
  and, where Metal passes, `metal`. Follow the style of `AnalyticShapeEdgeTest` and
  `LcdTextBackgroundsTest`. Probes that only explore stay in this kit.
- **Measure before changing.** Defaults were decided on measurements (ledger section 10); bring
  numbers with n and spread (median and IQR over at least 5 interleaved runs), never a single run.
- **Report faithfully.** Failing tests with their output, skipped steps as skipped, numbers that
  disagree with the VM or the Mac as disagreements, not as their errors.
- **Robot safety.** Never drive `java.awt.Robot` while any other window may own the keyboard:
  the scene driver refuses unless the isolated IDE is the foreground window (`IdeScenes --pid
  --fg fg.ps1`); keep that. A run on 2026-10-20 typed into the user's own IDE before the guard
  existed.
- **Records.** Append dated raw records in the ledger's style (bold date and title, then the
  facts with numbers, then what is open) to `notes/raw-<machine>-<date>.md` in your branch; the
  user merges them into the master ledger. Keep captures and logs under `build/skia-eval-out/`
  (git-ignored); commit only text and small crops.
- Naming in code: `SK` prefix, spelled-out names, camelCase properties, platform prefix first
  (`DawnSK...`, `Win32SK...`); precedent in the branch beats preference.

## 4. Phase 0: build and environment (half a day)

1. Toolchain: Visual Studio 2026 (the branch builds with `--with-toolchain-version=2026`; 2022
   may work), Cygwin with `make`, `autoconf`, `zip`, `unzip`, Python 3, a boot JDK 27 or 28 for
   x64 (Temurin, Liberica or Zulu). Build from Cygwin bash, not Git Bash (`make` lives there).
2. Prebuilt Skia: `bash jb/project/tools/common/scripts/fetch_skia.sh -t windows -a x64` prints
   the unpacked directory (verified against the digest in `skia_prebuilt.conf`).
3. Configure and build:

   ```
   bash configure --with-conf-name=windows-x86_64-skia --with-boot-jdk=<jdk27> \
        --with-toolchain-version=2026 --with-debug-level=release --with-skia=<prebuilt dir>
   make images CONF=windows-x86_64-skia
   ```

   The one image serves every arm; only system properties differ between arms. Alternatively
   `WITH_SKIA=1 bash jb/project/tools/windows/scripts/mkimages_x64.sh <n> nomodt`, the TeamCity way.
4. Record the machine once, in the first raw record: CPU, GPUs and driver versions, displays
   with resolution, scale and refresh rate, Windows build, power plan. Compile the kit's smoke
   sources and run `SkiaSmoke` with `-Dsun.java2d.skia=true -Dsun.java2d.skia.verbose=true`;
   keep the `Skia rendering enabled` line, it names the adapter Dawn picked. If the machine has
   an iGPU as well, note which one Dawn chose and whether the IDE's window sits on a display
   driven by the other.
5. IDE: install IntelliJ IDEA 2026.3 EAP through the Toolbox. **The Windows launcher honours
   `IDEA_JDK`** (verified on the VM): `idea64.exe` with `IDEA_JDK=<image>`, `IDEA_VM_OPTIONS`
   pointing at a copy of the stock vmoptions plus `-Dsun.java2d.skia=true
   -Dsun.java2d.skia.verbose=true -Dsun.java2d.d3d=false -Didea.trust.all.projects=true`, and
   `IDEA_PROPERTIES` pointing at a file that sets `idea.config.path`, `idea.system.path`,
   `idea.log.path` and `idea.plugins.path` under a scratch directory runs the IDE on the image.
   `text-quality/run-ide.sh` runs it through the JVM directly (launch parameters from
   `product-info.json`) and is what the scene runner uses; set `JDK`, `IDE` and `PY` in the
   environment or edit the three variables at its head.
6. Turn off notification toasts and Focus Assist for the measurement sessions; close other
   GPU users (browsers with video, meetings). Captures on the VM were contaminated by toasts.

## 5. Phase 1: correctness gate (half a day)

Run this before any performance number: a wrong pixel makes the number meaningless.

1. **jtreg Skia run lines:** `bash smoke/run-jtreg-skia.sh <image>` runs every `@run` line with
   `-Dsun.java2d.skia=true` of every test under `Graphics2D` and `TextRender` as plain java (no
   jtreg needed) and prints PASS or FAIL per line plus the eviction ratios. Expected: all pass;
   record the ratio. A test that fails only on your GPU is a finding (the test exists, now the fix).
2. **Rendering matrix:** `RenderMatrix skia`, `RenderMatrix software` (see the class header for
   the arguments) and `MatrixDiff` between them, at `uiScale=1`; the VM's numbers are above.
   `MatrixCells` and `MatrixList` pick cells for a closer look. Any cell beyond 32 levels that
   the VM did not have is a finding.
3. **Device loss.** With a Skia window up that owns VolatileImages (`SwingSet2Shot` or the smoke
   window), force a TDR with `dxcap -forcetdr` (Windows SDK Graphics Tools, elevated). Expected
   behaviour (implemented on the VM, never exercised for real): the device-lost callback marks
   the configuration lost, `Win32GraphicsDevice.displayChanged()` is posted once, top levels get
   fresh surfaces on a new device, a GDI stand-in paints while lost, VolatileImages report
   `IMAGE_INCOMPATIBLE` and are recreated, no crash, no black window that stays black. Then
   `DisplayChangeProbe` for the display-change path. Repeat the TDR with the IDE running on the
   image. Write down exactly what happened, with the log lines.
4. **Two adapters or two displays** (if present): a window with a cached `TexturePaint` dragged
   across displays on different adapters (cross-device sources are skipped with a warning by
   design; verify that nothing renders stale or crashes); mixed-DPI displays with `ScaleProbe`,
   `WindowSurfaceProbe` and `ViProbe`; `HugeSurfaces` for the adapter's texture limit.
5. **Pipeline selection:** `PipelineSelectionTest` is in the jtreg set; additionally confirm that
   `-Dsun.java2d.skia=true` alone disables Direct3D (the `Skia rendering enabled` line, and no
   Direct3D initialisation in the log).

## 6. Phase 2: micro performance (one day of machine time)

Arms, all on the one image (name them exactly like this in records):

| arm | properties |
|---|---|
| `d3d` | nothing (the Windows default pipeline, Direct3D 9) |
| `software` | `-Dsun.java2d.d3d=false` (what IDEA on Windows runs with today) |
| `opengl` (optional) | `-Dsun.java2d.opengl=true` |
| `vulkan` (optional, if the build has it) | `-Dsun.java2d.vulkan=true` |
| `skia-analytic` | `-Dsun.java2d.skia=true` |
| `skia-marlin` | `-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=marlin` |
| `skia-skia-msaa4` | `-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=skia -Dsun.java2d.skia.msaa=4` |
| `skia-strokes-java` | `-Dsun.java2d.skia=true -Dsun.java2d.skia.strokes=java` |
| `skia-lcd-twopass`, `skia-lcd-blend` | `-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass` or `blend` (text tests only) |
| `skia-scaler` | `-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia` (text tests only; new, expect rough edges) |

1. **RenderPerfTest** (`test/jdk/performance/client/RenderPerfTest`, build with `make` or
   `ant`; options `-n<iterations> -r<repeats> -e<exec mode>` and the font and text options, see
   the README and the `main` of `RenderPerfTest.java`). `jb/project/tools/perf/run_inc.sh`
   knows the Skia arms (`-skia`, `-skia-shapes`, `-skia-lcd`, `-skia-msaa`) but exits on
   `msys`; run it from Cygwin or loop RenderPerfTest yourself with at least 5 interleaved
   iterations per arm. Compare with `python bench/compare.py <a.log> <b.log>` (FPS table and
   geomean). **Check for vsync**: if FPS saturate at the display's refresh rate for every arm,
   the number is a cap, not a measurement; find the run mode that is not vsync-locked before
   recording (the TeamCity history has exactly this problem). Watch for skipped frames and
   hangs in the Robot exec mode (`RotatedBox_XOR` hung on the Mac in analytic mode; ledger
   section 13).
2. **Text:** `LcdTextBench <lines> 60 lcd|gray cheap [grid]` for 100 / 500 / 1000 lines and the
   90-line grid, in each LCD mode and in `software`; `bench/GlyphChurnBench <distinct glyphs>
   [lcd] [software]` at 200 / 600 / 1500 / 3000 (atlas eviction; Metal numbers in the ledger,
   2026-10-20 afternoon); `StartupCost` for the first frame (pipeline compilation; VM about 0.6 s).
3. **Footprint:** RSS and dedicated GPU memory of the RenderPerfTest process per arm (Task
   Manager's GPU memory columns, `nvidia-smi` or `amd-smi`, or the PDH GPU process memory
   counters), noted at the end of a run. The accumulate scratch (one destination-sized RGBA
   texture per context, never released) is a known cost; measure it at your display size.
4. Optional: package power during the text benches (HWiNFO or vendor tools), only if cheap.

## 7. Phase 3: the IDE (one to two days)

1. **Smoke:** IDE on the image through the launcher (above) with `-Dsun.java2d.skia.verbose=true`;
   open a real project and work for ten minutes: error count in `idea.log`, freezes
   (`threadDumps-freeze-*` directories), anything visibly wrong. Repeat with
   `-Dsun.java2d.font.scaler=skia`.
2. **Scenes and clusters (pixel conformance on real scales).** `text-quality/run-scenes.sh
   <arm> <light|dark> <scale>` starts the isolated IDE with the seeded configuration on the
   scene project, waits for indexing and the inspection pass, drives ten scenes with Robot and
   captures them; `SceneDiff` and `DeviationClusters --out ... --theme ... --scale ... --scene ...
   --arm ...` rank the differences against the software arm with crops. Before the first run:
   set the OS display scale (the IDE must run at the real scale, not `uiScale`), replace the
   jrt roots in `text-quality/seed/common/options/jdk.table.xml` with your image's path, extend
   the arm table in `run-ide.sh` (add `R1` = Direct3D 9, nothing but `-Dsun.java2d.skia=false`,
   and `A4` = `-Dsun.java2d.font.scaler=skia`), and read section 9 below. Matrix: arms `A0
   software`, `R1 d3d`, `A3 accumulate` (the default), `A1 blend` (the fidelity reference),
   `A4 scaler`; themes light and dark; scales 100 / 150 / 200 % (125 % once). Report per scene:
   pixels beyond 32 levels, mean over differing pixels, and the top clusters with their class
   (text, arc, IDE state).
3. **IDE performance.** If the ultimate repository and the Starter are available to you, follow
   ledger section 19 (scenes, metrics, arms, at least 5 interleaved repetitions). If not, the
   portable subset: (a) start-up to first frame per arm, 5 runs each (timestamps of the
   `AppStarter` start and the first `ProjectFrameHelper` line in `idea.log`); (b) a Robot-driven
   editor scroll and typing loop timed wall-clock per arm (the primitives are in
   `IdeScenes.java`; keep the foreground guard), 5 runs interleaved; (c) the IDE's own frame-time
   sampling if the EAP exposes it. Arms: `software`, `d3d`, `skia-analytic`, `skia-marlin`.
4. **Footprint in the IDE:** RSS and GPU memory after the scenes, per arm.

## 8. Phase 4: report and hand back

- One raw record per session in `notes/raw-<machine>-<date>.md`: machine, build (branch tip,
  prebuilt tag), numbers in tables with n, what differs from the VM and Mac expectations, defects
  with their tests and commits, what is open. Put a short summary at the top of the file that
  stands on its own.
- Push the branch to the fork; tell the user the branch name and the record's path.
- Do not modify the ledger snapshot in `notes/`; the master is on the Mac.

## 9. Pitfalls learned on the VM (each cost at least an hour)

- A scaled display makes `Robot` captures and `VolatileImage.getSnapshot()` resample; every pixel
  tool runs at `-Dsun.java2d.uiScale=1`, and the `Skia rendering enabled` line plus the surface
  class name (`SurfaceData.getPrimarySurfaceData(image).getClass()`) say which pipeline really
  rendered.
- IDE scene comparisons need byte-identical IDE state per arm: the runner wipes `config`, `log`
  and `system` per run, restores the project's `workspace.xml` and sources, waits for the
  indexer's `Finished for <project>` line (the dumb-mode exit comes 50 to 90 s earlier) and
  settles 120 s for the inspection pass. A stale `system` directory once produced a fake
  rendering defect that cost a day; when a difference appears in exactly one arm, dump what the
  IDE asked the backend to draw before forming a rendering hypothesis.
- The isolated IDE frame may come up small; the activation script maximizes it on every call.
  The scene driver waits for the Search Everywhere popup to be visibly up before typing,
  re-checks the foreground before every chord, and aborts (saving `popup-miss-N.png`) rather
  than type into an editor.
- Never overwrite a running bash script (the runner); bash reads it incrementally.
- Windows toasts land in captures; the bottom-right corner is excluded by default.
- `dxcap -forcetdr` needs an elevated shell and the Graphics Tools feature; it affects every
  process on the GPU, so close unrelated work first.
- Git Bash mangles arguments containing `:` and `/` (use `MSYS_NO_PATHCONV=1` for `git show rev:path`).

## 10. Tool index

`smoke/` (from the VM; compile with `javac --add-exports java.desktop/sun.java2d=ALL-UNNAMED
--add-exports java.desktop/sun.awt=ALL-UNNAMED -d out *.java` and run with the same exports):
`SkiaSmoke` and `SkiaRender` (first window, off-screen), `RenderMatrix` with `MatrixDiff`,
`MatrixCells` and `MatrixList` (the 1152-cell conformance matrix), `LcdTextBench`,
`LcdBackgrounds` (LCD fidelity table per background), `LcdOverlap` and `MissingInk`
(overlapping glyphs), `DisplayChangeProbe`, `WindowSurfaceProbe`, `ScaleProbe`, `ViProbe`,
`HugeSurfaces`, `ClearProbe`, `StartupCost`, `SwingSet2Shot`, `ScreenShot`, `Diff`,
`run-jtreg-skia.sh`.

`bench/` (from the Mac): `compare.py` (RenderPerfTest logs), `GlyphChurnBench`, `StrokeCompare`
(stroke routes against the loops), `run-eap-macos.sh` (reference for the JVM launch).

`text-quality/`: `TextSheet`, `TextSheetDiff` and `TextSheetCompare` (deterministic text sheet,
356 cells, every arm; `run-sheet.sh`), `IdeScenes`, `run-ide.sh`, `run-scenes.sh` and `fg.ps1`
(IDE scenes), `SceneDiff`, `DeviationClusters`, the probes (`ArcCoverageProbe` quantifies the
curved-edge class; `RRectEdgeProbe`, `EdgeRule`, `Contact`, `Zoom`, `Hot`, `Profile`, `Col`),
`seed/` (IDE configuration: Inter 13 and JetBrains Mono 13, subpixel AA, animations off, SDK
table, notifications off; `light/` and `dark/`), `text-scene/` (the project the scenes open).

`notes/`: the ledger snapshot, the text quality matrix plan (arms A0 to A7 and the dimensions),
and the two hand-over logs between the Mac and the VM (rounds 1 to 9), which show the working style.

## 11. The 2026-10-29 round: Skia as the software pipeline on Windows


Branch: `manuel.unterhofer/JBR-10654-skia-backend` at `d166dd1447e2` (origin and the fork `manu-unter/JetBrainsRuntime`). Work on
`manuel.unterhofer/JBR-10654-skia-backend-<machine>N` on the fork, never on the shared branch (HANDOVER.md §3). Everything
below runs with the GPU off, which is what ships on Windows (`-Dsun.java2d.d3d=false` is the IDE's default).

## 0. What arrived since the last hand-over (2026-10-28)

Chunk 1 of the long tail, "Skia as the software pipeline", is on the branch: `-Dsun.java2d.skia.raster=true` makes every
INT_ARGB / INT_ARGB_PRE / INT_RGB BufferedImage an `SKRasterSurfaceData`, drawn synchronously by Skia's raster backend
into the image's own pixels. Swing's back buffer on Windows without Direct3D is such a BufferedImage
(`VolatileSurfaceManager.getBackupSurface` → `BufImgSurfaceData.createData`), so with the flag the IDE paints through it.
Covered: AA shapes, Skia's stroker (AA, undashed), gradient paints, transformed images (nearest / bilinear / bicubic),
text through Skia's text path when `-Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia` (composite strikes slot by
slot), non-AA fills and wide strokes under the loops' three scan-conversion rules, the colour-only rectangle entry, the
INT_ARGB mirror kept between operations. Still the loops': thin non-AA lines (Bresenham, identical), XOR, custom
`Composite` and `Paint`, texture paints, other image formats, background-colour image draws, rotated text.
Ledger: `skia-rendering-ledger.md` §12 entries 2026-10-28/29, §13.8 (rules, open items), §18 Q23–Q26, §14 D10, §16 (JNI census).

On this Mac, against the software loops (1200 × 900, ms per frame, loops → raster, INT_ARGB_PRE): AA round-rects 9.7 → 5.0,
ovals 20 → 8.3, cubic blobs 13 → 5.2, stroked round-rects 11 → 7.3, gradient round-rects 18 → 11, bilinear icons 8.9 → 7.6,
rotated images 13.5 → 8.5, 400 strings 5.8 → 2.9 (Skia text path), AA rects 1.6 → 1.2, non-AA integer rects at parity,
non-AA curves at parity, bicubic images 43 → 56 and non-AA strokes 4.4 → 6.7 (the two rows behind the loops).

## 1. Build

As before (HANDOVER.md §4); the Skia library gained header dirs (`libawt/awt/image`, `cvutils`, `common/awt`) — a clean
`make images CONF=windows-x86_64-skia` after the rebase is enough, no reconfigure. `java -Dsun.java2d.skia.raster=true
-Djava.awt.headless=true` must print nothing to stderr for a `BufferedImage.createGraphics().fillRect`.

## 2. Correctness gate (half a day)

Run, in this order, with `-javaoption:-Dsun.java2d.skia.raster=true -javaoption:-Djava.awt.headless=true` unless noted:

1. `test/jdk/jb/java/awt/Graphics2D/SkiaRaster*Test.java` — nine tests (Surface *without* the javaoption: its second
   action checks the default stays on the loops; FixedCost, NonAA, Stroke, Paint, Image, Text, Mirror). **Text and the LCD
   check are the Windows-specific part:** `SkiaRasterTextTest` expects colour fringes and the loops' sub-pixel order for
   LCD-hinted text everywhere but macOS (the raster canvas declares BGR-horizontal geometry, which is Java's HRGB under
   the RGBA-over-BGRA swizzle — this has never run on a platform that renders sub-pixel masks). If the order comes out
   reversed, the fix is the geometry constant in `SKRaster.cpp` (`props`), nothing else. Record the ink ratios: on macOS
   Skia's plain coverage is 0.79 of the loops' glyph images; FreeType / DirectWrite hosts will differ.
2. The five JDK tests the Mac sweep flagged: `sun/java2d/SunGraphics2D/DrawRoundRect0Bug`, `sun/java2d/marlin/CrashNaNTest`,
   `sun/java2d/marlin/StrokedLinePerf` (must pass), `JoinMiterRedundantLineSegmentsTest` and `TestCreateStrokedShapeJoins`
   (known failures, Q26 / Q11: 3–4 levels on two pixels, two pixels of count).
3. The sweep itself, both arms (≈ 25 min each): `jtreg -k:!headful -conc:4 -javaoption:-Dsun.java2d.skia.raster=<on|off>
   -javaoption:-Djava.awt.headless=true test/jdk/java/awt/image test/jdk/java/awt/Graphics2D test/jdk/java/awt/geom
   test/jdk/java/awt/font test/jdk/sun/java2d`, then `comm -23` of the non-passed names (`grep -v 'Passed\.'` on
   `text/summary.txt`). Windows has loops the Mac does not exercise (GDI blits, `WinGraphicsEnvironment` fonts); any
   flag-specific failure is a finding — red test in `SkiaRaster*Test` first, then the fix.
4. `MetricsCensus` (still outstanding from the 2026-10-28 hand-over): the per-font summary lines for FreeType against Skia's
   host, the first Windows data point for work package 4.

## 3. Micro performance (half a day)

`build/skia-smoke/RasterShapesBench.java` (16 workloads; compile and run with `--add-exports java.desktop/sun.java2d=ALL-UNNAMED`;
`-Dsun.java2d.skia.raster=true|false`, image type 3 and 2) and `NonAARectLoop.java` (µs per `fillRect`), five interleaved runs
each, median and spread. Windows' loops are the same C code; the interesting rows are text (`-Dsun.java2d.font.scaler=skia
-Dsun.java2d.skia.text=skia` on the raster arm) and non-AA, where the Mac shows the rasterizers alone. RenderPerfTest
software vs raster: `-Dsun.java2d.d3d=false` on both, `-Dsun.java2d.skia.raster=true` on one.

## 4. The IDE (one day)

The IDE with `-Dsun.java2d.d3d=false -Dsun.java2d.skia.raster=true` (the Skia GPU backend *off*): the ten scenes of the
scene driver, pixel comparison against the software baseline with the usual tooling (HANDOVER.md §7), frame times from
the paint-time instrument. Expectations from the Mac numbers: AA shapes 2×, text 2× (with the Skia scaler), rectangles
parity, no visible differences beyond the triage classes (coverage model on AA edges, Q24/Q25 ties on non-AA curves).
A second run with the Skia text route (`-Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia`) is the first time
Skia draws the IDE's text on Windows through FreeType / DirectWrite; capture the editor and the settings dialog for the
catalog.

## 5. Pitfalls specific to this round

- Every raster native must stay at ≤ 14 parameter slots (HotSpot's fast signature handler; a `long` is two). Adding a
  parameter means packing one (`SKRasterShapePipe.flags / packed`).
- Device points travel as 16-bit halves: an image over 65535 px a side falls back to the loops by design.
- The INT_ARGB mirror's validity relies on `SurfaceData.markDirty` being called before every operation (it is, from
  `SunGraphics2D`); a new entry point that draws without it must call `untouchedByJava()` conservatively (pass no flag).
- `J2D_SKIA_RASTER_PROBE=0|1|2|3` stages an operation (entry / lock / draw) for measurement.

## 6. Report back

A `notes/raw-<machine>-2026-10-xx.md` with: the nine tests' lines, the LCD order verdict, the sweep's flag-specific list,
the census summary, the bench table (both image types) and RenderPerfTest, the IDE scene timings and any pixel findings.
