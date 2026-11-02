# Handover to the Windows VM — 2026-10-26 (evening): faster captures, setup changes, coverage gaps

Shared branch `origin/manuel.unterhofer/JBR-10654-skia-backend`, tip `7fd87d72737b`, also on the fork you pull. Pull first: it
carries your two commits, the macOS synthetic-style decision, the glyph-cache buckets on both pipes and **a Metal bucket bug fix**
(a glyph's cell must be looked up in the *active* cache; the Skia pipe's `SKGlyphCache_Draw` always did — the Dawn side is unaffected).
Ledger entries of the day: §12 2026-10-26 (several) and §19b.1.

## 1. The faster capture procedure (macOS numbers; port what applies)

A scene run went from ≈ 4.2 to ≈ 3.5 min (ten scenes, 2×, one theme); the IDE launch (≈ 75 s) is now the larger half.
- **Foreground check:** `IdeScenes.foreground()` called the shell script before every chord and typed string — 0.6 s per call on
  macOS (osascript), several per scene. It now talks to one long-lived helper (`text-quality-mac/fgfast.swift`, `--serve`: a line in,
  "pid|name|title" out, ≈ 25 ms). Your PowerShell `fg.ps1` per call is in the same cost class; the equivalent is a long-lived
  PowerShell (or a small compiled exe) reading commands from stdin and answering with `GetForegroundWindow` + `GetWindowThreadProcessId`.
  `IdeScenes.java`'s `foregroundFast()` is macOS-only by the `MAC` flag — add the Windows branch there.
- **Settle:** `capture()` no longer sleeps a fixed 2.5 s; `settle()` captures a coarse 48 × 30 grid of the screen every 300 ms and
  returns when two samples are equal (≥ 0.6 s, ≤ 2 × settleMs = 5 s: a blinking caret hits the cap). Shared Java in `IdeScenes.java`.
- `run-scenes.sh`: the 15 s after "exit dumb mode" is 5 s now; the first `settle()` does the rest.
- **Always look at one capture before trusting a round** — the trial run showed garbled glyphs that no sheet could show (the bug above).

## 2. What changed in the scene and sheet setup

- **Dark paper = white on black** (`TextReference.java`, `ctref.swift`, since 2026-10-26); it was the IDE's dark editor colours
  (188,190,196 on 30,31,34). Please switch your `TextReference` copy and `dwref` to the same pairing and re-capture, so the
  on-dark sheets compare across platforms; the colour sheets you made are unaffected.
- Sheet arms: `skia-shaper-cg` added (`text=shaper gray=cg`, the FS arm of the performance comparison).
- Scene arms: `F5` added to `run-ide.sh` / `run-scenes.sh` — the full Skia text route in the IDE
  (`-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text=paragraph`).
- A macOS colour sheet (`text-reference/run-colours.sh`): your seven colours × {white, black} paper, arms coretext, metal-platform,
  the hybrid, skia-scaler-cg at 16 / 256 / 1 luminance classes, coverage, skia-text-cg; filed as `<arm>/gray/<pos>/<scale>/<colour>-on-<paper>.png`.
  On macOS it tests the luminance-class quantization (no colour sub-pixels), not per-channel correction.
- `descriptions.json` schema: `dimensions[].labels` per platform (`"*"` + platform keys: the technology and who drives it),
  `dimensions[].effects` per value (what the value does to the pixels — feeds the viewer's "where the differences come from" box),
  `dimensions[].platform` (a Windows-only dimension such as `lcdSource` is dropped from other platforms' pages), `quirks[].kind`
  (`note` | `invalid` | `shortfall`, distinct banner styles), `presets` with `id` and `arms: {platform: [a, b]}` (one preset across
  platforms; a platform switch keeps it). The catalog is rebuilt by `compare-viewer/catalog/make-catalog.py platform=kind=path …`,
  which re-roots image paths and refreshes labels/effects/quirks/presets from `descriptions.json` — edit descriptions, not manifests.
- Viewer: fixed grid (no reflow), A/B as rows under one header, notes at the top right, presets in their own box, scales unified
  (1x, 1.25x, 1.5x, 2x; disabled where a platform has no set), "IntelliJ defaults" by the page's platform.
- Properties you may want in arms: `sun.java2d.skia.strikeCache` (MB, 64), `sun.java2d.skia.glyphCache` (texels), `sun.java2d.skia.glyphAtlas`
  (MB), `sun.java2d.skia.text.luminanceClasses` (1 … 256), `sun.java2d.skia.text.lcdColorBits` (yours), `sun.java2d.metal.glyphCacheSize` (mac).

## 3. Coverage: what macOS has and Windows does not (please implement / capture)

Computed from the manifests (`text-reference/out/2026-10-26-v15` and `-v16`, `text-quality-mac/out/2026-10-26/scenes` vs
`text-reference-win/out/2026-10-23-merged`, `out/2026-10-26`, `scenes-2026-10-23-evening`).

**Sheets — arms missing on Windows:**
| macOS arm | Windows equivalent to add |
|---|---|
| `chrome` (Blink + Skia reference) | the Chrome canvas reference (`compare-viewer/chrome-ref/render.sh`; needs Chrome on the VM) |
| `skia-text`, `skia-text-smoothed` | `text=skia` with Skia's own coverage (`gray` default) and with `text.smoothing=true` |
| `skia-text-cg` | `text=skia gray=platform` (Skia's text path with DirectWrite masks inside Skia's strike) |
| `skia-shaper`, `skia-shaper-smoothed`, `skia-shaper-cg` | `text=shaper` × {coverage, smoothing, `gray=platform`} |
| `skia-paragraph`, `skia-paragraph-cg` | `text=paragraph` × {smoothing, `gray=platform`} |
| `metal-skia-scaler` (gamma blend over Skia coverage) | `opengl` + `font.scaler=skia` (the OpenGL pipe's gamma over Skia's coverage), if the OpenGL pipe takes the Skia scaler |
| `metal-platform-plain` | n/a (no gamma pipe to switch off; `software` is the plain-blend baseline) |
| `coretext-unsmoothed`, `coretext-continuous` | n/a (DirectWrite's grid is nearest; `directwrite coverage` is the unsmoothed analogue you have) |
Present on both already: today's pipe (`software` / `opengl-platform`), the Skia pipe with the platform scaler, the hybrid
(`software-skia-scaler-platform` ≈ `metal-skia-scaler-cg-plain`), the platform-rasterized route (`skia-scaler-platform` ≈ `skia-scaler-cg`), coverage.

**Sheets — scales:** macOS has 1× and 2× (all it supports). Windows has 1.25× and 1.5× for four arms only (`directwrite`,
`skia-scaler-platform`, `skia-scaler-platformcoverage`, `software-skia-scaler-platform`); please extend them to **every arm**, the text-path
and shaper/paragraph arms included, so each Windows page has the same arm set at every scale.

**Scenes — missing on Windows:** macOS has M0 (today), M5 (hybrid), T0 (Skia pipe + platform scaler), T1 (Skia scaler coverage), T5
(platform-rasterized route), F5 (full Skia text route), light and dark at 2×. Windows has A0, D0, T3 (≈ T1-coverage on DirectWrite),
T4 (≈ T5) at 200 % only. Please add: **T0** (`-Dsun.java2d.skia=true` alone), **T1**, **S5** = the hybrid on today's pipe
(`-Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform`, software or OpenGL), **F5**, an **OpenGL-pipe** scene arm
(`-Dsun.java2d.opengl=true`, today's GPU alternative on Windows), and the scales **100 %, 125 %, 150 %** for all arms where the VM's display
settings allow. If the OpenGL pipe cannot take the Skia scaler's LCD glyphs, say so in the ledger rather than skipping silently.

**Colour sheets:** yours exist (five arms); macOS gets its own tonight (section 2). Nothing to add.

## 4. Still pending from your side (unchanged)
The whole-pixel advance and fractional-ppem rounding decisions; a Windows `@run` for `SkiaTextPathQueuedTest`, `SkiaStrikeCacheBudgetTest`,
`LuminanceClassCountTest`, `SkiaGlyphCacheBucketsTest`, `SkiaGlyphCacheStatsTest` (mac-gated only by the MTL configuration check).
