# Handover to the Windows VM — 2026-10-23: text reference, luminance probe, route analogue

Supersedes the request in `handover-vm-2026-10-22.md` (same goals, more precise now). Read
`build/notes/text-track-report.md` first: it has the macOS findings this asks you to mirror.

## 0. What happened on the Mac since round 9 (one paragraph)

The Skia font scaler is metric-exact and works on every pipe. CoreGraphics' font smoothing (AppKit's
text look) depends on the text colour's luminance only; a `cg` grayscale route rasterizes each glyph
through CoreGraphics for the text's luminance class and matches the native rendering within 0.3 %
ink and pixel for pixel where positions coincide. The strike key carries the luminance class
(`FontStrikeDesc.luminance`, 16 classes). Metal got `sun.java2d.metal.textGammaCorrection=false`
(plain blend, byte-identical to the software loops; the plain text shader had been dead). The viewer
(`build/compare-viewer/`) compares arms per dimension with switching, blending, difference and
stripes; `make-viewer.py` builds it over any sheet or scene tree.

## 1. Pull the branch and the tools

- Branch `manuel.unterhofer/JBR-10654-skia-backend` (commits up to "synthetic bold through CoreGraphics").
- Copy from the Mac (git-ignored): `build/compare-viewer/`, `build/text-reference/` (`TextReference.java`,
  `run.sh`, `ctref.swift` for the row definition), `build/notes/text-track-report.md`.
- `TextReference.java` is the Java side of the sheets: `java [props] TextReference <out> <rows.txt>
  <fonts-dir> [--volatile]` renders 24 sheets (aa × positioning × scale × paper) of 20 rows (family ×
  style) under `<out>/<aa>/<positioning>/<scale>/<paper>.png`. Change the two system families to
  **Segoe UI 13** and **Consolas 12** (both tools alike; the file fonts Inter and JetBrains Mono come
  from the JDK image's `lib/fonts`).

## 2. Native reference sheets on Windows (`dwref`, C++)

Same rows, same sheets, same colours (ink (30, 30, 40) on white; (188, 190, 196) on (30, 31, 34)), same
cell geometry (460 × 28 px per row at 1×, 920 × 56 at 2×; text at (4, 19) pt scaled), **no kerning**,
and the whole-pixel sheets at Java's rounded positions (file fonts: advances rounded in device pixels;
system fonts: in points — see `ctref.swift` for how the Mac tool does it with per-glyph positions).

Two native arms:
- **`directwrite`** — DirectWrite into a WIC/GDI-compatible bitmap through Direct2D (`ID2D1RenderTarget::
  DrawGlyphRun`) or `IDWriteGlyphRunAnalysis`: gray sheets = `D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE`,
  lcd = `D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE` with the system's ClearType parameters, aliased =
  `D2D1_TEXT_ANTIALIAS_MODE_ALIASED`; positioning fractional = `DWRITE_MEASURING_MODE_NATURAL`,
  whole-pixel = `GDI_CLASSIC` (and Java's rounded positions as above).
- **`gdi`** — `ExtTextOut` with `ANTIALIASED_QUALITY` (gray), `CLEARTYPE_QUALITY` (lcd),
  `NONANTIALIASED_QUALITY` (aliased); the JDK's Windows LCD glyphs come from GDI, so this is the
  reference for them.

If the Windows "default look" is ambiguous (DirectWrite in modern apps, GDI in the IDE's own JDK text),
keep both; the viewer shows them as two arms of renderer "native".

## 3. The luminance probe (the question that decided the Mac route)

Port `/tmp/polprobe.swift` (in the report, §3.2): draw "Hamburgefonts" in Segoe UI 13 with
DirectWrite (grayscale AA, then ClearType) and with GDI, as black on white, white on black, black on
mid-gray, white on mid-gray, mid-gray on white, mid-gray on black; sum |pixel − paper| and report the
gain of each AA mode over an aliased/plain-coverage render. **Question: does the Windows text stack's
weight depend on the text colour, on the background, or on neither?** Direct2D is documented to blend
text with gamma correction and "enhanced contrast" from `IDWriteRenderingParams`, which would make it
colour- and background-dependent at the blend, unlike CoreGraphics (text colour only, mask-level). The
answer decides whether a Windows analogue of the `cg` route is a per-luminance mask (as on macOS) or a
blend rule in the pipes (as JBR's Metal shader tried).

## 4. Java arms on Windows

With `TextReference.java` on the Skia Windows image (`-Dsun.java2d.uiScale=1` always):
`software`, `gdi-platform` or `d3d-platform` (whichever the default pipe is on the VM), `vulkan-platform`
if it initialises, `skia-platform` (`-Dsun.java2d.skia=true --volatile`), `skia-scaler-coverage` /
`darkened` / `smoothed` (`-Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=…`), and
`software-skia-scaler`. The `cg` route returns false on Windows (`DawnSKPlatform.cpp`), so there is no
`cg` arm yet — §3 decides what the analogue is.

Then `python3 make-viewer.py <out> --arms directwrite,gdi,software,… --set-dims aa,positioning,scale,paper`
and `bash <out>/viewer/serve.sh`.

## 5. LCD items that live on Windows

From the Mac (the IDE on macOS has no LCD, so these were parked):
1. The Skia scaler's LCD without fractional metrics comes out grayscale on macOS (scaler-side, both
   pipes; Skia core never ties the mask format to sub-pixel positioning). Check whether the FreeType
   host does the same.
2. LCD coverage convention: Skia linearises CoreGraphics' output with x²; FreeType applies its own LCD
   filter; the JDK's pipes expect the JDK's convention (reverse gamma 1.8 on macOS, FreeType's output
   elsewhere). Compare the `lcd` sheets' fringe statistics (pixels with channel spread > 48, mean, max)
   between `gdi`, `directwrite`, `software`, `skia-platform` and `skia-scaler-coverage`.
3. Cross-process stray pixels in LCD + fractional-metrics text (1–4 isolated coloured pixels per sheet,
   both scalers, every pipe on the Mac): render the same `lcd/fractional` sheet twice in fresh JVMs and
   diff; if Windows shows it too, the shared sub-pixel-variant glyph image path is the suspect.

## 6. Scenes

The scene pipeline (`IdeScenes`, `SceneDiff`, `DeviationClusters`) is yours already; the Mac added the
arms T5 (Skia pipe + `cg`) and M5 (Metal + Skia scaler `cg` + plain blend). On Windows the analogue of
M5 would be "today's pipe + Skia scaler + the Windows route", once §3 says what the route is. Until
then, run T1/T2 (coverage/darkened) against your baseline for the magnitude, and build the viewer over
the scene tree with `--set-dims theme-scale,scene`.

## 6b. Into the shared viewer

Build your trees' viewers with `make-viewer.py` as above and send the two `viewer/manifest.js` files
(or the trees); on the Mac they become `Windows:text:…` and `Windows:scenes:…` entries of
`make-catalog.py`, which gives the one viewer a platform select. Add Windows-specific quirks (things
that surprised you about DirectWrite, GDI or the pipes) to `descriptions.json` → `quirks` with `when`
conditions on arm and set dimensions; they appear as a banner when they apply.

## 7. Deliverables back

`out/<date>/` with the native and Java sheets and their viewer; the probe table of §3; the fringe
statistics of §5.2; a short note on §5.1 and §5.3; and anything the DirectWrite rendering does that the
Mac's assumptions do not cover.

## 8. Addendum (2026-10-23 night): what changed on the Mac since this was written

Pull the branch again: eleven commits since "synthetic bold through CoreGraphics", up to
`eda8385d22e6`. Nothing here changes §1–§7; it adds arms, tests and build inputs you will meet.

**New text routes (all behind `-Dsun.java2d.font.scaler=skia`):**
- `-Dsun.java2d.skia.text=skia` — Java's glyph codes and positions drawn by Skia's own text path
  (strike, atlas, blending); `-Dsun.java2d.skia.text.smoothing=true` asks the host for smoothed masks
  (Chrome's configuration; on Windows that is Skia's DirectWrite host — measure what it does).
- `=shaper` — `drawString`/`drawChars` shaped by SkShaper (HarfBuzz + Skia's ICU, kerning and
  ligatures off like Java's drawString, platform font manager for fallback). Layout-neutral on the
  Mac at 1×; at 2× whole-pixel the system fonts differ (Java rounds advances in points, Skia in
  device pixels) — check whether Windows has the same rule for its system fonts.
- `=paragraph` — attributed strings through SkParagraph (fonts, colours, underline, strikethrough;
  anything else falls back to TextLayout). The underline is not pixel-snapped.
- `-Dsun.java2d.skia.text.gray=cg` **on these routes**: a proxy typeface per text-luminance class
  (`SKLuminanceTypeface.cpp`, on Skia's internal `src/core/SkScalerContext.h`) takes its A8 masks
  from `SKPlatform_SmoothedGlyphMask`. On Windows that hook returns false (`DawnSKPlatform.cpp`), so
  the proxy falls back to the wrapped typeface's masks: the route is a no-op there until §3 decides
  the Windows analogue. The proxy itself must compile and behave as a pass-through on MSVC — a quick
  check that `text=skia gray=cg` equals `text=skia` pixel for pixel on Windows would confirm it.

**Build:** `lib-skia.m4` now links `skparagraph`, `skshaper`, `skunicode_icu`, `skunicode_core` and
`icu` from the prebuilt (HarfBuzz is inside `skia.lib`; do NOT add `harfbuzz.lib`). Verify the
Windows prebuilt of the same tag ships these `.lib` files and the `modules/skshaper`,
`modules/skparagraph`, `modules/skunicode` headers; configure fails loudly if a library is missing.
While you are in the link line: `mincore.lib` and `winmm.lib` in `AwtLibraries.gmk` have no recorded
reason — try linking without each and record which Dawn symbol needs them (§13.2 cleanup item (c)).

**Tests to run on the Windows image** (`test/jdk/jb/java/awt/Graphics2D/TextRender/`): `SkiaTextPathTest`,
`SkiaShaperTest`, `SkiaParagraphTest`, `SkiaTextPathCoreGraphicsTest` are `@requires os.family == "mac"`
for now — port their expectations if the routes initialise on Dawn (the counters are platform-neutral).

**Sheets and viewer:** `run.sh` has 23 arms; the new ones (`skia-text[-smoothed]`, `skia-shaper[-smoothed]`,
`skia-paragraph`, `skia-text-cg`, `skia-paragraph-cg`) need the matching `-D` lines in your runner.
`descriptions.json` gained the dimension "Text layout & shaping" (`java2d` / `skia shaper` /
`skia paragraph` / `native` / `chrome`): give every Windows arm `layout: java2d` (or the Skia value)
so the column stays consistent in the catalog. Dimensions may declare `"column": true` to stay a
radio column.

**Notes:** the text-track report (`build/notes/text-track-report.md`) §1 items 7–8 and the ledger §12
entries of 2026-10-23 (afternoon → night) describe the four stages and their measurements.

**Cleanup commit (after the addendum above):** `JBR-10654 Skia: text-track cleanup` renames the strike
interface `sun.font.SkiaDrawable` → `SKDrawableStrike`, and `lib-skia.m4` now **fails configure** when the
release lacks `skparagraph`, `skshaper`, `skunicode_icu`, `skunicode_core` or `icu` — so on the VM the
first symptom of a Windows release without the text modules is a configure error naming the library,
not a link failure. `SkiaScalerWithoutBackendTest` is `@requires os.family == "mac"` now, like the
other text-route tests; port, do not just un-gate.
