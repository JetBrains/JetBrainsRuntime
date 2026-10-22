# Text rendering quality matrix — Windows (plan, 2026-10-20, VM)

Purpose: one repeatable process that produces comparable visual samples of text for every text
rendering strategy the Skia backend has or will have on Windows, against the baseline users see
today, so that quality decisions (ledger §14 D9 and the path-a rungs of §17) are taken on the same
pictures. Performance is measured elsewhere (§19, `LcdTextBench`); this plan is about appearance
and layout, with timing recorded only as a side column.

## 1. Baseline and arms

Baseline: **the software loops** (IDEA on Windows runs with `-Dsun.java2d.d3d=false`: Swing paints
into a software back buffer, text comes from Java's FreeType scaler, grayscale through `MaskFill`,
LCD through the per-channel gamma loops, blitted to the window through GDI). Every arm below is
diffed against this.

| arm | what differs from the baseline | switch | state |
|---|---|---|---|
| A0 software loops | — | `-Dsun.java2d.skia=false` (and D3D off) | baseline |
| A1 Skia/Dawn, Java glyphs, destination-read blender | GPU compositing of Java's glyph images; exact LCD blend, layered on overlap | `-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend` | exists; slow on Dawn, the fidelity reference among GPU arms |
| A2 Skia/Dawn, Java glyphs, two-pass | gamma folded into the coverage table; layered on overlap | `…lcd=twopass` | exists, Windows default |
| A3 Skia/Dawn, Java glyphs, accumulated coverage | same blend, coverage unioned in a scratch texture | `…lcd=accumulate` | exists |
| A4 Skia/Dawn, **Skia-rasterised glyphs**, Java metrics and layout | glyph images from `SkFont` (FreeType inside Skia: its hinting, LCD filter, gamma pre-blend), composited as today; advances still Java's | planned value of the text-engine property (§17 "glyph rasterization" rung; §10 2026-10-12 keeps it as an experiment rung) | planned |
| A5 Skia text engine | `sun.font` on `SkFont`/`SkShaper`: scaler, shaper and metrics from one engine; Swing re-flows | `sun.font.engine=skia` (§17) | planned (path a, WP 3.2) |
| A6 Skia paragraph layout | `TextLayout`/`LineBreakMeasurer` on SkParagraph, requires A5 | `sun.font.paragraph=skia` | planned |
| A7 Skia-native text draw | glyph runs drawn by `SkCanvas::drawGlyphs` with Skia's own atlas and LCD path (dual-source blending on D3D11), bypassing `AccelGlyphCache` | an experiment switch on top of A4/A5 | optional rung, worth one row because it is the "all Skia" end point |
| R1 Direct3D 9 pipeline | OpenJDK's other GPU text path (`D3DTextRenderer`), what users without `d3d=false` get | `-Dsun.java2d.d3d=true -Dsun.java2d.skia=false` | reference only |
| R2 native Windows text | DirectWrite/ClearType as the OS renders it (Notepad, Terminal, VS Code at the same font and size) | manual screenshots | reference only: what Windows users consider "right"; Java's LCD never matched it |

Mixes worth a row because they isolate one variable: A4 isolates rasterisation (same layout as
A0, different glyph pixels); A5 with `shapes=marlin` isolates the layout change from everything
else; A2/A3 against A1 isolate the gamma approximation. Mixes that do not isolate anything
(Skia shaping with Java glyphs) are not run.

Secondary pipeline knob to hold fixed unless a row needs it: `sun.java2d.skia.shapes=analytic`
(default) — it changes icons and round rects next to the text, not the text.

## 2. Dimensions

| dimension | values | notes |
|---|---|---|
| anti-aliasing | **LCD** (`VALUE_TEXT_ANTIALIAS_LCD_HRGB`, the IDE's "Subpixel"), **grayscale** (`ON`, "Greyscale"); `OFF`/`GASP` once at 100 % 12–13 px only | the IDE has separate settings for IDE and editor text; both are set the same per run |
| device pixel ratio | **100 %**, **150 %**, **200 %**; 125 % dropped (150 % is the fractional case, 125 % adds little) | `-Dsun.java2d.uiScale=…` for the micro sheet; the real display scale for the IDE scenes (on the VM: Parallels display setting), captured with `createMultiResolutionScreenCapture` |
| theme | **Light** and **Dark** (the New UI defaults: "Light", "Dark"); text colours and backgrounds taken from them for the micro sheet | the dark theme is where the two-pass gamma approximation is weakest (light text on #1E1F22 / #2B2D30) |
| content | **UI**: main menu, tool-window tabs and headers, project tree with icons, editor tabs, buttons/checkbox labels, tooltip, status bar, Settings dialog (tree + a form), Search Everywhere popup. **Editor**: Java file with keywords (bold), comments (italic), strings, numbers, annotations, a selection and the caret row, line numbers and gutter icons, code vision / inlay hints (smaller size), a folded region, soft-wrapped line, ligatures (`->`, `!=`, `>=`), a line with CJK and emoji, a Markdown preview, Block Terminal output | the §19 scenes already drive the editor and the Settings dialog; the sheet below covers the combinatorics the IDE cannot |
| fonts | **Inter** (UI) and **JetBrains Mono** (editor); fallback: Segoe UI (UI fallback), Microsoft YaHei / Yu Gothic (CJK fallback), Segoe UI Emoji (colour glyphs, BGRA path), Consolas (what users with "system font" get) | bold, italic, bold italic; ligatures on and off for JetBrains Mono |
| sizes | UI: 12, 13, 14; editor: 11 (inlays), 12, 13, 14, 16, 18, 24, 36 (presentation mode); terminal 13 | 13 px at 100 % is the default pair; 11–14 is where hinting and the LCD filter decide the look |
| colours / backgrounds (micro sheet) | text on: white, #F7F8FA (light panel), #1E1F22 (dark editor), #2B2D30 (dark panel), #808080, selection blue (#2675BF, light; #214283, dark), a gradient, an image | the LCD arms are exact on white/black and diverge on mid-tones: these rows carry the decision |
| text state | opaque colour (LCD allowed); translucent colour / `AlphaComposite` (Java downgrades LCD to grayscale: all arms must agree); XOR text once | `SKSurfaceData.canRenderLCDText` gating is a correctness row |
| positioning | integer positions; fractional positions with `java2d.font.subpixelResolution` (the JBR default in IDEA, verify the current value) and with `KEY_FRACTIONALMETRICS` on; text under a 0.5-px translate (scrolling) | sub-pixel glyph variants (§18 Q14) are what A4/A5 would replace |
| LCD contrast | default (140) and 100, 180 once | `KEY_TEXT_LCD_CONTRAST`; the table is per contrast |
| OS text settings | ClearType on (default) and off; Windows "Make text bigger" at 100 % | `awt.useSystemAAFontSettings` follows ClearType unless the IDE overrides it |
| scripts (A5/A6 rows only) | Latin, Cyrillic, Greek, CJK, Arabic (RTL, shaping), Devanagari (reordering), combining marks, emoji sequences | layout correctness, not pixels: compare advances, line breaks, caret positions |

Dimensions a reader may expect and we deliberately keep small: hinting modes (JBR exposes none
beyond the scaler default; A4/A5 bring Skia's, which is a row of its own), font weights beyond
bold (variable Inter weights are an A5 topic), printing (not in scope).

## 3. Two instruments

**3a. Text sheet (micro level, deterministic, every arm).** A Java program after `LcdBackgrounds`
(`build/skia-smoke/TextSheet.java`): renders a grid of cells into a compatible `VolatileImage`
(so the arm under test owns the surface) and the same grid into a `BufferedImage` with the
software loops, writes both as PNG plus a JSON/CSV manifest (cell → font, size, style, AA, colour,
background, positioning, contrast). Cells: fonts × styles × sizes × AA × (colour, background) ×
positioning, with a fixed pangram ("Hamburgefonstiv ffi fl Wq AVfi 0123 {}[]()->!=") and a code line
for JetBrains Mono. Run once per (arm, scale, ClearType setting). The sheet is the arm-agnostic
part: A4–A7 run it unchanged once their switches exist.

**3b. IDE scenes (macro level, what users see).** The isolated IDEA instance from §19
(`build/skia-bench/idea/run-idea-skia.sh`, `idea.vmoptions` per arm, `idea.properties`,
pre-seeded `options/` with theme, fonts, AA setting, animations off, caret blink off, no clock, fixed
window bounds, one demo project with the editor file described above). Scenes: (1) editor view at
the top of the file, (2) editor with selection and a code-vision line, (3) Project tree +
editor tabs + status bar, (4) main menu open, (5) tooltip over a gutter icon, (6) Settings dialog
"Editor > Font", (7) Search Everywhere with results, (8) Block Terminal after `dir`, (9) Markdown
preview, (10) presentation mode editor. Driven by the Starter/Driver scripts of §19 or, cheaper
for the first iteration, by `Robot` key sequences from a small launcher. Captured with
`Robot.createMultiResolutionScreenCapture` (device pixels), cropped to named regions
(`regions.json`: editor-line-5, menu-item, tree-row, tab-title, tooltip, status-bar). Run per
(arm, theme, scale, AA setting): arms A0–A3 at {Light, Dark} × {100, 200} × {LCD, gray}; 150 %
for A0 and the default arm only; A4–A7 join when they exist.

Both instruments write under `build/notes/text-quality/<date>/<arm>/…` (Mac master copy, as the
ledger) with an `index.md` of tables and crops.

## 4. Measures

Per cell or region, against A0 at the same scale, plus against A1 for the GPU arms:

- **Pixel statistics**: max and mean channel difference on text pixels, counts beyond 8 and 32
  levels (as `LcdTextBackgroundsTest`), ink-missing count (as `MissingInk`), with a heat map.
- **Weight**: ink mass (sum of coverage, per channel for LCD) relative to A0: text reads
  heavier or lighter — the number the dark-theme gamma question reduces to.
- **Fringe**: for LCD, mean channel imbalance at glyph edges (|R−B| over the edge pixels): colour
  fringing more or less than Java's filter.
- **Layout** (A5/A6, and A4 as a control that must be 0): per string, advance width and
  per-glyph positions from `FontMetrics`/`GlyphVector` against A0 (drift in px and in % of width),
  line-break positions of a paragraph, caret positions; per-font tables of the ascent/descent/
  line-height change. This is the re-flow magnitude WP 3.2 asks for.
- **Human review**: a crop sheet per scene region, 4× nearest-neighbour magnified, arms side by
  side with A0 first and R2 last, labelled; reviewed on a 100 % display and on a 200 % display.
  Perceived weight and sharpness do not follow the pixel statistics closely enough to skip this.
- **Side column**: paint time of the scene (`runtimePerformanceTesting` `rendering` scenario or
  the AWT paint counter) so a quality decision sees its cost.

## 5. Process

1. Build the text sheet and the diff/crop tooling (one day; the diff and ink tools exist, the
   crop sheet and manifest are new). Validate on the VM with A0–A3 at 100 % and 200 %.
2. Seed the IDEA instance (theme, fonts, AA, demo file with every editor feature above) and
   script the ten scenes (two days). First with Robot sequences; move to the Driver when §19 Phase 2
   lands.
3. Run both instruments on the VM for A0–A3 (half a day per scale), publish the index, take the
   first decision on `twopass` vs `accumulate` vs `blend` per theme from the dark-theme rows.
4. Repeat on the user's x64 machine with a discrete GPU (same scripts; adds ClearType on/off and a
   real 150 % display) and on the Mac for A1 on Metal (same sheet, `sun.java2d.skia.lcd=blend`),
   so the cross-platform appearance is one table.
5. When the A4 switch exists: run the sheet and the scenes; the layout measure must read 0 (same
   metrics), the pixel measures show Skia's rasteriser against Java's. This is the appearance
   triage §10 (2026-10-12) asked for before committing to A5.
6. When A5/A6 exist: run everything plus the scripts dimension; the layout measure becomes the
   headline (re-flow magnitude per font and size), the pixel measures the footnote.
7. Each run appends a dated entry to ledger §11 with the headline numbers and a link to the
   index; decisions go to §10 and §14 (D9 for the LCD strategy, a new D-row for the rasteriser).

## 6. Things that were easy to forget

- Colour emoji and other BGRA glyphs (a separate draw path in every arm).
- Fallback fonts: the CJK and symbol glyphs come from other fonts and other scalers' habits.
- Synthetic bold and italic (fonts without the face; Java emboldens the outline).
- Text with alpha (hover fades, disabled labels): Java switches LCD off; all arms must agree.
- Text over selection, caret row, error stripes and gradients: the mid-tone backgrounds.
- Fractional positions and sub-pixel glyph variants (§18 Q14), scrolling by 0.5 px.
- Ligatures and contextual alternates in JetBrains Mono (shaping, not rasterisation, but they
  change which glyph images exist).
- ClearType off at the OS level, and a run on a display with vertical or BGR subpixel order
  (`VALUE_TEXT_ANTIALIAS_LCD_HBGR` / `VRGB`): the arms must honour the order the strike asked for.
- A translucent window or popup (tooltips, the Islands look): non-opaque destination → grayscale.
- Mixed-DPI: a window dragged between 100 % and 200 % displays, strikes re-created per scale.
- High-contrast mode (Windows "Contrast themes"): colours only, but it is a reviewer's expectation.
- Printing and `BufferedImage` export are the software loops regardless of pipeline: the baseline
  is also what the IDE's "copy as image" produces; a visible difference between screen and export
  is itself a finding.
