# Text on the Skia backend — report of the text track (JBR-10654, WP4)

*State as of 2026-10-23. Everything below was measured on this machine (Apple M1 Max, macOS 26, built-in
2× display, IntelliJ IDEA 2026.3 EAP on the Skia JBR image); the evidence is browsable in the comparison
viewers listed at the end. Windows is still open (see the handover).*

## 1. Summary

1. **A Skia font scaler exists and is metric-exact.** Ascent, descent, leading and fractional advances
   are identical to the platform scaler's for every font, size and scale probed; whole-pixel advances
   are identical once the JDK's two rounding rules are followed. Swing components keep their preferred
   sizes: **no re-flow**.
2. **Rasterization is where the engines differ, and the differences were measured against the
   platform itself**, not against today's JDK: a CoreText/CoreGraphics reference tool draws the same
   rows, and the IDE scenes compare against today's Metal pipeline.
3. **The decisive finding:** CoreGraphics' font smoothing — the look of all AppKit text, governed by
   `AppleFontSmoothing` — depends on the **text colour's luminance only**, not on the background
   (+15 % ink for black text, +40 % for white, +21 % for mid-gray). No colour-independent glyph mask
   can reproduce it; a mask rasterized by CoreGraphics **per luminance class** can, exactly.
4. **That route is implemented** (`sun.java2d.skia.text.gray=cg`): the glyph is drawn by CoreGraphics
   for the luminance class of the text colour, the strike key carries the class, and the sheets match
   the native rendering **within 0.3 % ink on light and dark paper**. Since 2026-10-24 the route also
   places glyphs on CoreGraphics' own quantization grid (thirds of a pixel at 1×, halves at 2×, rounded
   down, which is what AppKit draws by default): a glyph through the route is **byte-identical to
   CoreGraphics at every sub-pixel offset**, and the JetBrains Mono and Menlo rows of the sheets differ
   from AppKit's rendering by at most one level. It costs up to 7 extra strikes per font and size in the IDE's dark theme and ≈ 4 ms once
   per new class and font.
5. **The route does not need the Skia renderer.** The glyph mask comes from CoreGraphics and any pipe
   composites it plainly; Metal does so with its JBR-3820 brightness exponent switched off
   (`sun.java2d.metal.textGammaCorrection=false`, which also revived a dead shader path). **Metal with
   this route and the Skia pipe with this route produce the same IDE pixels.** macOS text quality is
   therefore separable from the renderer decision.
6. **Today's JDK text on macOS is a compromise in two places:** file fonts (JetBrains Mono, Inter) are
   FreeType coverage, 16–25 % lighter than AppKit's smoothed text, and the Metal shader's brightness
   exponent compensates for the dark theme at the expense of the light one (−26 % / −12 % against
   AppKit; the software loops −16 % / −25 %).
7. **The full-Skia text path, first two stages (2026-10-23):** drawing Java's glyphs through Skia's
   own text path gives Skia's look (its gamma model; 35 % lighter than AppKit with Skia's defaults,
   Chrome's look with the platform's smoothing requested, verified against Chrome 156), not the
   native one. Shaping by Skia's shaper (`sun.java2d.skia.text=shaper`: HarfBuzz, Skia's Unicode
   module and font fallback) is **layout-neutral**: pixel-identical to Java's layout on the sheets
   once HarfBuzz's default kerning and ligatures are off, as in Java's drawString. Skia's paragraph
   engine behind `drawString(AttributedCharacterIterator)` (`=paragraph`: fonts, colours, underline,
   strikethrough) places the glyphs exactly where the shaper does; only the underline differs, drawn
   at the font's underline metrics without pixel snapping, up to one pixel above Java's and CoreText's.
   Stage 4 closes the appearance question: a proxy typeface per text-luminance class delivers
   CoreGraphics' smoothed masks inside Skia's own strike and atlas (`gray=cg` on the text path), and
   the result is **byte-identical to the glyph-image cg route** on every sheet, i.e. within 0.3 % ink
   of the native rendering. The full-Skia text route can have the native look; the per-luminance
   dependence maps onto Skia's strike keying without touching its gamma machinery.
8. **Open:** fallback-font parity (one visible symbol run; also Skia's own fallback in the shaper
   route), Windows (DirectWrite reference and the analogue of the route), LCD items that only matter
   where LCD is the default (Windows/Linux), and the remaining stages of the full-Skia text path.

## 2. What was built

| piece | where | what |
|---|---|---|
| Skia font scaler | `sun.font.SKFontScaler`, `SKFontStrike`, `libawt_skia/SKFontScaler.cpp` | typefaces from the platform's Skia host (CoreText on macOS; `CFont`'s own `CTFontRef`), metrics, advances with the JDK's rounding rules, outlines, glyph images (A8, LCD triplets, sub-pixel variants, colour glyphs), platform fallback fonts; selected with `-Dsun.java2d.font.scaler=skia`; works on every pipe |
| grayscale routes | `sun.java2d.skia.text.gray=coverage\|darkened\|smoothed\|cg` | plain coverage (= Skia's and Chrome's unhinted text), two emulations, and the CoreGraphics-per-luminance route |
| luminance classes | `FontStrikeDesc.luminance`, `FontInfo.luminance`, `SunGraphics2D.textLuminance()` | 16 classes of 16 levels from the current colour; strings and glyph vectors; other scalers ignore it |
| Metal plain blend | `sun.java2d.metal.textGammaCorrection` | the JBR-3820 exponent on/off; off = byte-identical to the software loops |
| synthetic bold | in the cg route | the glyph drawn twice, 1/24 em apart (WebKit's method; CoreGraphics does not smooth stroked glyphs) |
| Skia's text path | `sun.java2d.skia.text=skia`, `SKTextRenderer`, `SKBackend_DrawGlyphsBySkia` | Java's glyph codes and positions drawn by Skia's strike, atlas and blending; `sun.java2d.skia.text.smoothing=true` requests the platform's smoothed masks (Chrome's configuration) |
| Skia's shaper | `sun.java2d.skia.text=shaper`, `SKBackend_DrawShapedBySkia` | drawString/drawChars shaped by HarfBuzz through SkShaper with Skia's ICU and font fallback, drawn as one text blob; kerning and ligatures off as in drawString; glyph vectors keep Java's layout |
| Skia's paragraph engine | `sun.java2d.skia.text=paragraph`, `AttributedTextPipe`, `SKBackend_DrawParagraphBySkia` | attributed strings laid out and decorated by SkParagraph (fonts, colours, underline, strikethrough); other attributes stay with TextLayout |
| CoreGraphics masks in Skia's strike | `SKLuminanceTypeface.cpp`, `gray=cg` with `text=skia\|shaper\|paragraph` | a proxy typeface per luminance class whose scaler context takes A8 masks from CoreGraphics and delegates the rest; a wrapping font manager for the fallback runs |
| Chrome reference | `build/compare-viewer/chrome-ref/` | Chrome 156 canvas text with the same rows and fonts, kerning off, rendered headless |
| CoreText reference | `build/text-reference/ctref.swift` | the same 20 rows × 24 sheets drawn by CoreText/CoreGraphics; AppKit's default (smoothing on) and the unsmoothed variant; Java's whole-pixel layout reproduced; kerning off |
| sheets | `build/text-reference/run.sh` | 23 arms × 24 sheets (antialiasing × positioning × scale × paper), rows family × style |
| IDE scenes | `build/text-quality-mac/` | ten Robot-driven IntelliJ scenes per arm and theme, 2×; arms M0 (today), T0 (Skia pipe), T1/T2 (coverage/darkened), **T5** (Skia pipe + cg), **M5** (Metal + cg + plain blend) |
| viewer | `build/compare-viewer/` | switch/flip/blend/flicker/difference/stripes at integer or continuous zoom without interpolation, radio columns per dimension, documentation panel, IntelliJ-defaults button |
| tests | `test/jdk/jb/java/awt/Graphics2D/TextRender/` | `SkiaFontScalerMetricsTest`, `GlyphVectorBoundsTest`, `FallbackGlyphsTest`, `SkiaScalerWithoutBackendTest`, `CoreGraphicsGrayRouteTest` (luminance dependence, same-process colours, glyph vectors, synthetic bold), `MetalTextPlainBlendTest`, `SkiaTextPathTest`, `SkiaShaperTest`, `SkiaParagraphTest`, `SkiaTextPathCoreGraphicsTest` — each written red before its change |

## 3. Measurements

### 3.1 Metrics and layout (T1, T3)
- 480 strike configurations × 95 glyphs (Dialog, Helvetica Neue, Menlo, JetBrains Mono, Inter; 8–32 pt;
  1/1.25/1.5/2×; fractional metrics on/off): ascent, descent, leading and fractional advances
  **identical**; whole-pixel advances identical once the two JDK rules (CStrike rounds in points,
  FileFontStrike in device pixels) are followed. Kept as a jtreg test (168 strikes exact).
- Swing component sheet (14 components, 1× and 2×): **no preferred-size difference**; pixels within 29
  (1×) / 42 (2×) levels, ink ratio 1.002 / 1.001.
- IntelliJ on macOS already runs UI and editor text with **fractional metrics** (registry `Auto`
  resolves to on since Catalina), and **without sub-pixel AA** (refused on macOS unless two system
  properties are set). The relevant sheets for the IDE on macOS are therefore gray + fractional.

### 3.2 What the platform draws (reference tool)
- CoreGraphics emits **no colour sub-pixels** on this macOS, in any bitmap format or polarity. AppKit's
  "LCD font smoothing" is grayscale dilation. The JDK's LCD glyphs on macOS are JDK-made (through the
  private JavaRuntimeSupport style API) and have no native counterpart.
- Smoothing gain over plain coverage, Helvetica Neue 13 (Σ|pixel − paper|): black on white **+15 %**,
  black on mid-gray +15 %, white on black **+40 %**, white on mid-gray +41 %, mid-gray on white +21 %,
  mid-gray on black +22 % → a function of the **foreground luminance only**; the composite itself is
  linear in the destination.
- Skia's own CoreText host models this (luminance in the strike key, an sRGB preblend, a halved
  luminance on this smooth behaviour) but renders once in black on white and applies its model: a
  model of CoreGraphics, not CoreGraphics. Chrome's look, not AppKit's.

### 3.3 Ink against the native rendering (Inter 13, fractional, Σ|pixel − paper| per row)

| arm | on white 1× | on dark 1× | on white 2× | on dark 2× |
|---|---|---|---|---|
| **native, AppKit default** | 207,754 | 168,073 | 827,811 | 672,500 |
| **Skia pipe + cg route (T5)** | 207,207 | 167,668 | 826,523 | 671,558 |
| **Skia's own text path + cg masks in its strike** (= T5 byte for byte) | 207,207 | 167,668 | 826,523 | 671,558 |
| **whole Skia route (paragraph + shaper + text path) + cg masks** | 207,207 | 167,668 | 826,519 | 671,555 |
| **Metal + cg route + plain blend (M5)** | 207,214 | 167,659 | | |
| native unsmoothed | 175,949 | 125,319 | | |
| Skia coverage route | 175,174 | 126,196 | 698,388 | 503,029 |
| software loops, platform scaler (today's images) | 174,796 | 125,909 | 701,134 | 504,988 |
| Metal, platform scaler (today's IDE) | 154,587 | 148,710 | 660,904 | 550,712 |
| Skia's own text path, Skia defaults | 136,057 | 148,636 | 617,467 | 547,612 |
| Skia's own text path, platform smoothing (= Chrome) | 200,217 | 170,955 | 812,587 | 638,580 |
| **Google Chrome 156, canvas text** | 200,917 | 169,970 | 813,876 | 636,644 |
| Skia's shaper + text path, platform smoothing | 200,217 | 170,955 | 812,605 | 638,566 |
| Skia's shaper + text path, Skia defaults | 136,057 | 148,636 | 617,490 | 547,595 |
| Skia smoothed route | 263,003 | 189,455 | 939,622 | 676,848 |
| Skia darkened route | 208,805 | 150,424 | 813,618 | 586,057 |

Skia's shaper against Java's layout, same text path and masks: **0 differing pixels on every row at 1×**
(fractional and whole-pixel, both papers); at 2× one Inter regular glyph differs (32 pixels). Skia's
HarfBuzz path rounds the advances when sub-pixel positioning is off, so the whole-pixel rows coincide
with Java's rounded advances as well.

Skia's paragraph engine against the shaper, same text path: the 16 plain rows are pixel-identical; in
the 4 underlined rows the glyphs are where the plain rows' are (centroid difference 0.00 px against
the regular row of the same font) and the underline accounts for the 2.2–2.6k differing pixels per
row at 1×: it follows the font's underline position and thickness without pixel snapping (Inter 13:
spread over y = 20/21 where Java's and CoreText's fill y = 21).

With CoreGraphics masks inside Skia's strike (stage 4), Skia's text path from Java's glyph codes is
byte-identical to the Skia pipe's glyph-image cg route on every sheet (0 differing pixels at 1× and
2×, whole-pixel and fractional, both papers). The whole Skia route differs from it only in the
underlined rows, one quarter-pixel Inter glyph at 2×, and the Helvetica Neue rows of the 2×
whole-pixel sheet, where Java rounds a system font's advances in points and Skia's shaper in device
pixels.

Placement (2026-10-24): the native reference now quantizes like AppKit; against it the glyph-image cg
route is within max 1 level for JetBrains Mono and Menlo at 1× and 2× (all four styles), Helvetica Neue's
regular rows likewise (its style rows 25 px > 32 at 1×), and Inter within max 1–2 once the reference
stops applying Inter's default contextual alternates (Java's drawString applies no OpenType features;
the reference now switches them off like kerning). Skia's
own text path cannot follow the rounding-down grid (it positions on quarters, nearest) and is now the
less faithful of the two cg arms on fractional sheets.

Pixel for pixel (whole-pixel sheets, identical positions): the cg route equals the native rendering
within **1–2 levels, no pixel beyond 8**, for JetBrains Mono, Helvetica Neue and Menlo on both papers;
Inter matches except where glyph positions differ (a few advances rounding differently, and the
fallback symbols).

### 3.4 IDE scenes against today's IDE (M0, Metal, platform scaler; light editor, 2×)

| arm | pixels differing | beyond 32 levels | max | mean |
|---|---|---|---|---|
| T0 Skia pipe, platform scaler | 168,189 | 16 | 33 | 10.2 |
| **T5 Skia pipe + cg** | 178,484 | 6,190 | 251 | 14.4 |
| **M5 Metal + cg + plain** | 153,890 | 6,189 | 251 | 16.4 |
| T1 Skia coverage | 180,201 | 49,917 | 251 | 25.9 |
| T2 Skia darkened | 189,036 | 36,503 | 251 | 20.4 |

Dark editor: T5 8,004, M5 7,999, T1 69,830, T2 26,640 beyond 32. T5 and M5 have **no cluster beyond
32 between them** outside the project-badge corner: same images, same picture. The T5-vs-M0 clusters
are the fallback symbol run (`∑∂Δ → ⇒`, different fallback font and spacing) and the italic mid-gray
comment line (CoreGraphics' +21 % for mid-gray text against today's FreeType coverage — the luminance
effect itself). The Skia pipe alone (T0) changes the IDE's text by at most 33 levels anywhere in the
light theme.

### 3.5 Cost
- Cold rasterization per glyph: platform 20 µs, Skia coverage 21 µs (+8 %; LCD +20 %), **CoreGraphics
  route ≈ 47 µs**; warm redraw identical in every route.
- The dark theme's 16 text colours fall into **8 luminance classes**; 3 fonts × 13 pt → 21 strikes
  instead of 3; the first draw of a colour with a new class ≈ 4 ms for 84 glyphs, once; repeat draws
  0.03 ms; the font info is renewed by a compare when the colour's class changes. Glyph-image memory
  scales with the classes in use (tens of KB per 13-px strike).

### 3.6 Determinism
- Single-process rendering is deterministic in every route. **LCD + fractional metrics** text shows 1–4
  isolated pixels that differ between processes, with the platform scaler and the Skia scaler alike,
  on every pipe: a pre-existing issue in the JDK's sub-pixel-variant glyph image path, irrelevant to
  the IDE on macOS (no LCD), to be fixed before Windows LCD sheets are judged pixel-exactly.

## 4. Consequences for the decision

- **macOS text quality is not a Skia question.** The route that matches AppKit by construction is a
  rasterizer and strike-key change usable by today's Metal pipeline; the Skia renderer neither helps
  nor hinders it. "Path c plus this route" and "path b plus this route" draw the same text.
- **Path a's text risk changes shape.** A full-Skia text path (Skia's strike, atlas and blending; later
  SkShaper/SkParagraph) was built as a first stage (`sun.java2d.skia.text=skia`). With Skia's defaults
  it draws dark text 35 % lighter than the platform; with the platform's font smoothing requested
  (`sun.java2d.skia.text.smoothing=true`, Skia's hinting "normal" on the CoreText host) it matches
  **Google Chrome within 0.6 % ink** — measured against headless Chrome drawing the same samples — and
  stays within 2–5 % of AppKit (−3 % on white, +1 % on dark at 1×). Native fidelity inside Skia's
  architecture would need a custom scaler context feeding the per-luminance CoreGraphics masks into
  Skia's strike. The gain of path a for text is therefore the paragraph/shaping API and one engine for
  layout and rasterization at Chrome's fidelity, not AppKit's. Stages 2–4 follow (see §6).
- **Today's compromise** (FreeType for file fonts, the Metal exponent) is visible in the numbers and
  could be improved independently of either path.
- **Windows decides the LCD questions**, where LCD is the IDE default and FreeType, GDI and DirectWrite
  meet; the handover asks for the reference sheets and the analogue of the luminance measurement.

## 5. Open items (text)
1. Fallback-font parity: the Skia route resolves fallbacks with `CTFontCreateForString`, the JDK with
   its own CoreText call; route the former through the latter (≈ 1 day).
2. Windows reference and route analogue (handover `build/notes/handover-vm-2026-10-23.md`).
3. LCD: Skia's coverage convention vs the JDK's, the grayscale output for LCD without fractional
   metrics, the cross-process stray pixels — all Windows/Linux-relevant.
4. Non-colour paints get the property fallback luminance (gradients on text are rare in the IDE).
5. The `smoothed` and `darkened` routes are superseded on macOS by `cg`; keep or drop at cleanup.
6. Shaper and paragraph routes: paragraph direction is left to right (Java takes it from the first
   strong character); SkParagraph's underline is not pixel-snapped and stays anti-aliased on aliased
   text (the glyphs take the strike's edging, positioning and hinting); the Skia text path draws
   LCD-requested text grayscale on Graphite; attributes other than font, colour, underline and
   strikethrough stay with TextLayout; the dylib growth from skshaper, skparagraph, skunicode and ICU (≈ 30 MB of static
   archives) is to be measured; under Skia's layout the whole-pixel advances of system fonts at 2×
   round in device pixels where Java rounds in points (Helvetica Neue rows differ, Menlo's coincide).
7. The remaining Java2D exceptions of the Skia text route and the JDK-wide fallback move are listed
   as tiers in the ledger (§13.1, 2026-10-23 evening): the cheap tier is about a week.

## 6. What comes after this report (agreed 2026-10-23)
1. Windows handover; when the VM is back, a cleanup and polish run over the branch together with the
   backend review items (§13.2 of the ledger) that must land before the Skia pipe is a baseline.
2. Then the evaluation of the **full-Skia text route**: shaping (SkShaper), layout (SkParagraph),
   rasterization (Skia's scaler context), atlas and blending all by Skia — against the same sheets and
   scenes, with the per-luminance question answered for that architecture. **Started ahead of the
   cleanup while the VM is out (2026-10-23): stage 1 (Skia's text path: Skia's look, Chrome's with
   smoothing), stage 2 (Skia's shaper: layout-neutral) and stage 3 (Skia's paragraph engine for
   attributed strings: glyphs layout-neutral, underline unsnapped) and stage 4 (CoreGraphics masks
   inside Skia's strike: byte-identical to the glyph-image route) are done. The evaluation of the
   full-Skia text route is complete; what remains is the exception list above.**

## 7. Evidence
- Text sheets: `build/text-reference/out/2026-10-23-v13` (23 arms × 24 sheets incl. Chrome, Skia's text
  path, Skia's shaper, Skia's paragraph engine and the CoreGraphics masks inside Skia's strike), viewer `…/viewer/serve.sh`; the catalog with the scenes:
  `build/compare-viewer/catalog/serve.sh`.
- IDE scenes: `build/text-quality-mac/out/2026-10-22/scenes-v2` (M0, T0, T1, T2, T5, M5 × dark/light),
  reports `scenes-{dark,light}-2x.md` (against M0) and `*-baseT0.md`, viewer `…/viewer/serve.sh`.
- Probes in `build/text-reference/probes/`: `polprobe.swift` (luminance dependence), `fmtprobe2.swift`
  (bitmap formats), `LuminanceStrikeBench.java` (classes and cost), `Ink.java`, `PixStats.java`,
  `ShiftStats.java` (sheet statistics).
- Ledger: `build/notes/skia-rendering-ledger.md` §5, §12 (2026-10-22/23), §13.1, §16, §18.
