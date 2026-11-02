# Hand-over to the Mac host — 2026-10-29: LCD text on the GPU pipe's Skia text path, and what it changed

Follows `handover-mac-2026-10-28.md` (the raster round; its asks 1–5 stand). Branch **`manuel.unterhofer/JBR-10654-skia-backend-windows10`**
on the fork, now your tip `244f32e7ff26` + 11 commits; record `jb/project/tools/skia-eval/notes/raw-vm-2026-10-28.md` (sections 2026-10-28
and 2026-10-29). No co-author trailers anywhere.

## 1. The finding

The user's F5 IDE (Dawn + Skia scaler + paragraph route + platform masks) showed no sub-pixel AA at 1×, while the catalog's F5
capture had fringes. Both are right: **the 2026-10-27 F5 capture is pixel-identical to T4 in the editor and the menu** — no IDE
text went through Skia's text path then, because the path refused composite strikes and every Swing UI font on Windows is one
(the editor's too). Since `24fbe9a05076` the UI text takes the path, and there every LCD request died: **`SKBackend_GetSurface`
wrapped the window's backend texture with `nullptr` surface properties** — an unknown pixel geometry makes Skia's glyph painter
downgrade sub-pixel edging to A8 before a mask is made, so Graphite's LCD step (`BitmapTextRenderStep`, A565 masks,
`kBitmapText_LCD`) was never reached. Not a Graphite limitation (I claimed that for an hour on the strength of the grayscale
sheets; the user pointed at the Graphite source). The paragraph-route LCD *sheets* were grayscale on every image for the same
reason. On macOS nothing visible changed, since CoreText renders no sub-pixel masks — which is presumably why it went unnoticed.

## 2. The commits (please review; two touch your design)

| commit | what |
|---|---|
| `78398bf0f7bb` | **The window surface declares its pixel geometry** (`SkSurfaceProps` on `WrapBackendTexture`). Red `SkiaTextPathLcdTest` (LCD HRGB on a compatible VolatileImage through `text=skia` and `=paragraph`, the loops with the same scaler's glyph images as reference): 0 fringe pixels vs the loops' 1570; green 1550 / 1690 (logical / file font), order agreement positive. Paragraph-route LCD sheet 0 → 33939 fringe pixels at 1×. |
| `88f401e39748` | **The order is the display's, LCD_HBGR maps to a BGR geometry.** New hook `SKPlatform_LcdOrderIsBGR()` (Windows: `SPI_GETFONTSMOOTHINGORIENTATION`, the desktop hint's source; **macOS: `false`, blind edit in `MTLSKPlatform.mm`**), `-Dsun.java2d.skia.lcdOrder=system\|rgb\|bgr`, **`SKBackend_Configure` has one more parameter** (`SKLcdOrder`), `SKBackend_LcdPixelGeometry()` shared by the GPU window surface and the raster surface (mirrored on an RGBA Skia build). A geometry is a surface's, not a draw's: a draw whose own hint names the other order gets the surface's — the IDE's hint is the desktop hint, so the real case is covered. Red/green: the test's `bgr` run line, agreement −15.4M → +15.4M. |
| `03ede8c00a9b` | `LcdGlyphSourceTest` needed `sun.font:+open` under real jtreg. |

Review points: the two-pass LCD scratch surface (`SKBackend.cpp` ~1724) keeps default props — right for the glyph-image blend,
worth a look if the text path ever draws into it; `SKPlatform_LcdOrderIsBGR()` on macOS; whether the hook should also drive the
`SKRaster` props on macOS (it does, through `SKBackend_LcdPixelGeometry()`, RGB there).

## 3. What was re-rendered (catalog rebuild needed)

- **Sheets:** the eight text-path arms (`skia-text`, `skia-text-smoothed`, `skia-text-platform`, `skia-shaper`, `-smoothed`,
  `-platform`, `skia-paragraph-smoothed`, `skia-paragraph-platform`) and the colour sheets of `skia-text-platform`, re-rendered
  **in place** in `text-reference-win/out/2026-10-27-sheets/` (viewer rebuilt, 88 sets / 1448 images). LCD everywhere now
  (`skia-text` 1× black on white: 6310 → 33840 fringe pixels), order = the loops' and DirectWrite's. The manifest path is the one
  you already have: `Windows=text=text-reference-win/out/2026-10-27-sheets/viewer/manifest.js` — **please rebuild the catalog**.
- **Scenes:** F5 re-captured on the Retina display at 200 % (all eight groups, valid), tables against A0 and T4 rebuilt in
  `text-reference-win/scenes-2026-10-27/`. F5 is its own arm now: vs A0 editor means 18.1 / 13.5 (light / dark 2×), 18.9 / 14.2
  (1×); vs T4 light 2× editor 29.0 (before ≈ 0, by construction). The crops show ClearType weighting and placement differences
  of Skia's DirectWrite host against the platform route's masks, same glyphs at the same places. Unchanged and still valid:
  every other arm of the 2026-10-27 set and the 2026-10-28 raster set.
- Not re-rendered: nothing else needed it (the inference is in the record).

## 4. Skia test set on the image, real jtreg (`jb/java/awt/Graphics2D` incl. `TextRender`)

43 passed, 13 skipped by platform, 2 failed before the two test fixes: `LcdGlyphSourceTest` (`+open`, fixed) and
`SkiaRasterFixedCostTest` type 2 at 1.545 vs 1.5 — the third borderline reading on this VM (1.54, pass, 1.55); INT_ARGB_PRE
0.77. The limit is tight for this ARM core; your call whether 1.5 stays.

## 5. Asks (the five of 2026-10-28 stand)

6. **Compile-check the Mac side** of `88f401e39748` (`MTLSKPlatform.mm` hook, the `SKBackend_Configure` signature) and
   re-run `SkiaTextPathLcdTest` there (it should print "nothing to test").
7. **Rebuild the catalog** (sheets and F5 scenes changed under the same paths).
8. **Close §13.1 "the Skia text path's LCD request is grayscale on Graphite"** for Windows, or reword it as a macOS-only
   observation.
9. Still open from 2026-10-28: the paint-time instrument (ask 1), the `ClipShapeTest` dashed modes under the raster flag, the
   slow RenderPerfTest rows (ask 5).
