# Skia backend evaluation (JBR-10654) — the kit

The tracked home of the evaluation of Skia as JBR's rendering backend: the records of what was
found and decided, the tooling that produces the artefacts (IDE scene captures, text sheets, the
comparison viewer and the published catalog, benches, probes), and the procedure every machine
follows. Since 2026-11-02 this directory on the branch `manuel.unterhofer/JBR-10654-skia-backend`
*is* the hand-over between machines: nothing of lasting value lives in untracked folders any more.

Start with `records/ledger.md` (§0 the status board, §10 the decision log, §13 the open items),
then the latest `records/raw/raw-<machine>-<date>.md` for the machine you are on.

## Layout

| directory | what it holds |
|---|---|
| `records/ledger.md` | the ledger: context, findings (§11), work log (§12), open items (§13), design notes; updated every session on the integrating Mac |
| `records/raw/` | dated raw records per machine (`raw-vm-…`, `raw-x64hw-…`), the VM's text-reference notes |
| `records/handovers/` | the hand-over notes exchanged so far (historical; the earlier kit guide `HANDOVER-2026-10-22-x64hw-kit-guide.md` still documents the benches and the Windows side in depth) |
| `records/reviews/`, `records/plans/`, `records/metal-pr/`, `records/artifacts/` | review rounds, plans and reports, the Metal PR texts, small result images |
| `capture/` | the IDE scene capture: one driver (`IdeScenes.java`) and one in-IDE plugin (`scene-driver/`) for both platforms, the wrappers `run-scenes.sh` (dispatches to `-mac`/`-win`), `run-ide-*.sh`, the seeds, the scene project, the window helpers (`fgfast.swift`, `fg.ps1`, `fg.sh`), the display check (`ScreenBounds`), the diff tools (`SceneDiff`, `DeviationClusters`, `Contact`, `Zoom`), the text-sheet renderer used on Windows (`TextSheet*`, `run-sheet.sh`) |
| `text-reference/` | the text sheets: the macOS reference renderer (`ctref.swift`), the Java twin (`TextReference.java`), `run.sh` / `run-colours.sh`; `win/` the Windows side (`dwref.cpp`, `run-win.sh`, probes) |
| `viewer/` | the comparison viewer (`make-viewer.py`, `viewer.js`, `index.html`, `serve.sh`), the vocabulary of every arm and dimension (`descriptions.json`), the catalog assembler (`catalog/make-catalog.py`), the packaging (`make-dist.py`) and publishing (`publish.sh`) of https://manu-unter.github.io/text-rendering-catalog/, the Chrome reference renderer (`chrome-ref/`) |
| `bench/` | `RenderPerfTest` and `RasterShapesBench` runners and arms |
| `smoke/` | probes and small benches (`SkiaSmoke`, `RenderMatrix`, `PipelineProbe`, …) |
| `env.sh`, `viewer/kitpaths.py` | where the repository, the Skia image and the generated output are |

Everything generated goes to `build/skia-eval-out/` (git-ignored; `SKIA_EVAL_OUT` overrides): `capture/`
(IDE state, classes, the helper, the plugin, index snapshots, captures), `text-reference/`, `catalog/`,
`dist/`, `dist-cache/`, `smoke/`. Captures and sheets are never committed.

## Mode of operation

- **Branches.** The shared branch is `manuel.unterhofer/JBR-10654-skia-backend` on `origin` and on the
  fork `manu-unter/JetBrainsRuntime`. A machine works on `manuel.unterhofer/JBR-10654-skia-backend-<machine>N`
  on the fork and never pushes to the shared branch; the integrating Mac cherry-picks from the machine
  branches, updates `records/ledger.md` and pushes the shared branch to both remotes.
- **Records.** Every finding, measurement and decision of a session goes into that machine's dated
  record in `records/raw/` (the VM continues `raw-vm-<date>.md`, the x64 machine `raw-x64hw-<date>.md`),
  committed with the code it belongs to. The ledger is the Mac's; machines do not edit it.
- **Tests first.** Every bug gets a jtreg test under `test/jdk/jb/java/awt/Graphics2D` that is red before
  the fix and green after, folded into the guarding commit. Commit messages in JBR style, no trailers.
- **Measurements.** n ≥ 5 interleaved runs, median and spread, the image build and the machine named.
- **The driver only drives the isolated IDE.** `run-scenes.sh` starts its own IDE from the seed; the
  driver's commands go to that IDE's built-in server, its captures are that frame. Nothing is typed.
- **Captures are produced by every machine and assembled here.** A machine runs the capture routines
  and delivers the resulting trees — nothing else: no viewer of its own, no catalog, no private
  descriptions file. The Mac builds the viewers, assembles the catalog, packages and publishes it.
  Delivery: the VM through its shared folder into `build/skia-eval-in/<machine>/` on the Mac, other
  machines as an archive attached to the hand-over. A capture tree is
  `<kind>/<date>/<arm>/<theme>-<scale>x/<scene>.png` plus `arm.txt` (the IDE's `Skia rendering enabled`
  line) for scenes, and the nested sheet layout of `text-reference/run.sh` for text. New arms are
  described in `viewer/descriptions.json`, here, in git.

## Procedures

**Scenes** (`capture/`): `compile.sh` (the driver and tools into `build/skia-eval-out/capture/classes`,
the helper on a Mac), `scene-driver/build-plugin.sh` once per IDE build (`IDE_HOME=<install dir>` on
Windows; the plugin is compiled against that IDE's libraries), then
`run-scenes.sh <arm> <light|dark|both> <scale> [out-dir] [scenes]`. The wrapper waits until a display runs
at the scale with room for the 1728×980 frame and tells you what to set; the first run of an IDE build
indexes and saves an index snapshot, later runs clone it. About 28 s per arm for light and dark on a
Mac. Arms are the cases in `run-ide-mac.sh` / `run-ide-win.sh`; an arm code with an `f` appended (`M0f`)
is the same arm with fractional glyph placement everywhere (the IDE's registry keys
`ide.text.fractional.metrics` and `editor.text.fractional.metrics`, read from system properties), its
captures filed under `<theme>-<scale>x-fractional` — a sample condition (`placement`) of the viewer, meant
for Windows (macOS places every text fractionally by default; measured 2026-11-03, zero difference). Scenes since 2026-11-03: `workspace` (tree, the editor with its find-and-replace
panel on "Comparable", a four-line selection and the quick documentation popup, the terminal), `settings`
(Appearance & Behavior › Appearance) and `markdown`. Verify one capture of a round before
trusting it (`Contact out.png cols width imgs…`).

**Text sheets** (`text-reference/`): `run.sh [out]` on macOS (CoreText reference, the Java arms, the
Chrome reference, the colour sheets), `win/run-win.sh [out]` on Windows (DirectWrite and GDI reference
through `dwref`, the Java arms). The Java arms are every combination of the text settings the platform
offers, generated by `arms.py <macOS|Windows>` (92 on macOS, 2026-11-03): an arm's directory name spells
its settings (`pipe-skia+scaler-skia+strike-java2d+…`) and the viewer reads its dimensions from the name
(`kitpaths.arm_from_name`), so no per-arm entry in `descriptions.json` is needed; the Windows runner
renders `arms.py Windows` (52 arms) the same way, with the ClearType colour sheets for the blend-system arms
of the Skia scaler.

**Viewer and catalog** (`viewer/`): `make-viewer.py <tree> --arms … [--set-dims …] --title …` installs a
viewer into `<tree>/viewer`; `catalog/make-catalog.py` refreshes the catalog from the trees it was
assembled from (matched by the trees' titles; a new tree replaces an entry by carrying the old title, or
the entry's title is edited in the generated manifest); `make-dist.py --workflow`; `publish.sh "message"`
(one force-pushed commit to the Pages repository; the user runs it).

**Benches** (`bench/`): `run-rpt-arms.sh` (Windows) and `run-rpt-mac.sh` (macOS) run RenderPerfTest over
interleaved arms, `rpt-summary.py` summarises; `run-raster-bench.sh`; records go next to the numbers in the
machine's raw record.

## Status of the other platforms

The Windows side of the rebuilt capture routine (`run-scenes-win.sh`, `fg.ps1 -Serve`, the plugin build
with `IDE_HOME`, the plain-copy index snapshot) is written but unverified; the VM verifies it first and
then re-captures the Windows scenes with it (the catalog's Windows scenes are still the ten-scene
capture of 2026-10-27). Linux has not started.
