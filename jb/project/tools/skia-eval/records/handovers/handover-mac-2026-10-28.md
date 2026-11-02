# Hand-over to the Mac host — 2026-10-28: the raster surface verified on Windows, and what we need back

From the Windows ARM64 VM (Parallels), answering your hand-overs of 2026-10-28 (census) and 2026-10-29 (Skia as the
software pipeline). Everything below ran on `windows-aarch64-skia-main`, built from your shared tip `244f32e7ff26`.

## 1. Where the code is

Fork branch **`manuel.unterhofer/JBR-10654-skia-backend-windows10`** = your tip + seven commits, pushed; no touch of
the shared branch. Please fold in, reword as you see fit (no co-author trailers anywhere).

| commit | what |
|---|---|
| `e16587c3d1d4` | `SkiaFontScalerMetricsTest`: the Windows reference is the platform's exact strike (the two metric decisions, see §4). Cherry-picked from `…-windows9`; **its message names the wrong cause** (head flag) — see `f86f2596793b`. |
| `58eec3997377` | **Build and colour on Windows.** `colordata.h` is a libawt *Windows* header (`libawt/windows` added to the Skia library's header dirs on Windows). `SKRaster.cpp` asserted an RGBA Skia build — **the Windows Skia build is BGRA** (`SkTypes.h`: `SK_R32_SHIFT 16` under `SK_BUILD_FOR_WIN`). Pixels stay N32; `kSwizzle = (kN32 == kRGBA_8888)` gates the red/blue swizzle and the BGR geometry declaration; a BGRA build takes Java's ARGB ints as they are and declares `kRGB_H`. Red with only the assertion removed: 6 of 8 raster test files, red and blue exchanged everywhere. |
| `bcb7b1e85bd6` | `SkiaRasterTextTest`'s LCD row draws on **INT_RGB**: the JDK renders LCD text onto opaque destinations only (`SurfaceData.canRenderLCDText`), so an LCD hint on INT_ARGB_PRE is grayscale under every pipe, the loops included (measured: 0 fringe pixels for both). INT_RGB is an `SKRasterSurfaceData` too and Swing's back-buffer format. **Please re-measure your macOS bounds for that row** (CoreText host, opaque image). |
| `f86f2596793b` | Metrics-test comments corrected: FreeType's whole-ppem sizes at 125 % / 150 % come from **embedded bitmap strikes** (`FT_Match_Size`; Courier New ships strikes at 4–21 px), not from the integer-ppem head flag — all six census fonts carry the flag and all six measure exact. |
| `f5846f822d83` | **Finding, fixed:** text through Skia's text route on an INT_ARGB raster surface took 86× the INT_ARGB_PRE time. `SKRasterTextPipe` handed the natives the *clip* as the operation's box; the mirror is written back for the box per operation — the whole image per string. Now the glyph list's image bounds (`GlyphList.getBounds`) + 2 px within the clip; nothing drawn when no glyph pixel is inside. Red `SkiaRasterTextCostTest` (400 strings, INT_ARGB vs INT_ARGB_PRE) ratio 86, green 1.9. |
| `4a5acda2a2c4`, `840acee133a8` | The raw record `jb/project/tools/skia-eval/notes/raw-vm-2026-10-28.md` (all tables, n and spread). |

The VM-side scripts of this round live in `build/` (git-ignored) and are described in the record: `jtreg-raster.sh`
(the gate with a real jtreg 8.3, downloaded to `build/deps/jtreg-download`), `run-census.sh`, `run-raster-bench.sh`,
`run-rpt-raster.sh`, `run-raster-scenes.sh`; probes `RasterLcdProbe`, `DashClipProbe`, `headflags.py`, `sbits.py`,
`pngdiff.py`, `fringes.py`.

## 2. Correctness gate — results

- **The nine tests (ten run lines, now a tenth file):** all pass on the fixed image. `SkiaRasterFixedCostTest` failed
  once on the red-step run at ratio 1.54 vs 1.5 (INT_ARGB) — VM timing noise, passed on every later run; see the
  RenderPerfTest FlatBoxAA row in §3 before trusting the 1.5 on this hardware.
- **LCD order verdict:** the loops' order (agreement +13.8M), fringes 1599 px on the Dialog line — on an opaque image.
  Ink ratios, Skia's DirectWrite host vs the loops' glyph images (FreeType's): grayscale 0.725 (drawString, drawChars,
  drawGlyphVector alike), translucent 0.768, LCD 1.169 (macOS: 0.79 grayscale).
- **Your five JDK tests:** `DrawRoundRect0Bug`, `CrashNaNTest`, `StrokedLinePerf` pass; `JoinMiterRedundantLineSegmentsTest`
  4 levels on one pixel (Q26), `TestCreateStrokedShapeJoins` two pixels of count (Q11) — as predicted.
- **Sweep** (454 files, `-k:!headful -conc:4`, 9 min per arm): on 285 passed / 45 failed / 4 error, off 289 / 40 / 5.
  **Flag-specific = the two above + `marlin/ClipShapeTest#CubicDoDash` and `#PolyDoDash` (new, open, §5).**
  Nothing in the off-only list.

## 3. Performance (medians of 5 interleaved runs, IQR in the record; 8 vCPU ARM64 VM)

`RasterShapesBench`, INT_ARGB_PRE, ms per frame, loops → raster: rrect fills 12.8 → 6.8 (0.53), ovals 24.2 → 10.0 (0.41),
cubic blobs 16.5 → 5.9 (0.36), stroked rrect 13.6 → 11.2, AA rect converter 2.4 → 1.8, gradient rrects 21.9 → 14.9,
radial 19.4 → 16.6, bilinear icons 11.9 → 9.0, rotated images 16.3 → 10.5, **bicubic 118 → 62** (the opposite of your
43 → 56), text rows (loops' glyph images) at parity, non-AA fills at parity, **non-AA stroked rrect 6.4 → 9.3 (1.44)**.
INT_ARGB (the mirror): AA fills 0.66–0.78, non-AA 1.1–1.9× slower, Skia-text rows 7.1 / 6.5 ms (were 370 / 333 before
`f5846f822d83`; INT_ARGB_PRE 4.4 / 4.1). `NonAARectLoop`: 0.68 → 0.62 µs per `fillRect`.

**RenderPerfTest `-e=buffer -t`** (a translucent compatible image = INT_ARGB, the mirror path; 19 tests): faster —
FlatOvalAA 0.54, FlatQuadAA 0.76; parity — RotatedOvalAA, gradients, images, text (loops' glyph images), Lines, FlatBox.
**Slower under the flag:** FlatBoxAA **2.7×** (1000 small AA boxes: the per-operation fixed cost on this core), LinesAA
**2.6×** (Skia's stroker for one-pixel AA lines vs the loops' AA line loops), non-AA FlatOval **2.3×** and RotatedBox
**5.1×** (the non-AA rules on the raster surface vs the span iterators), RotatedBoxAA 1.9×, ClipFlatOvalAA 2.2× (a clip
per primitive). Numbers to weigh against the scenes before any rule changes; the small-box and thin-line rows are the
ones the IDE paints most.

**Census** (`MetricsCensus`, six fonts × 96 configurations): metric drift **0.000** for every font; advances differ only
in whole-pixel rows (the rounding rule, decision 1): flips Inter 0.26 %, JetBrains Mono 0, Segoe UI 5.8 %, Consolas
12.4 %, Arial 19.9 %, Times 24.5 %; worst string-width drift Consolas 49 px (8 pt × 1.25, 189 of 190 glyphs a pixel
narrower under FreeType's hinting). No fractional row differs in a single advance.

## 4. IDE scenes (user's go-ahead), `text-reference-win/scenes-2026-10-28-raster/`

A0 (loops, re-captured on this image), R0 (A0 + `skia.raster=true`), R5 (R0 + `font.scaler=skia skia.text=skia`),
light/dark × 200 %/100 %, GPU backend off; 12 runs, all valid. **R0 vs A0: antialiased shape edges only** (borders,
icons, inlay pills, squiggles; text untouched), ≈ 1450 px beyond 32 per scene at 2×, means 3–13 — the coverage class.
**R5 vs A0: every glyph re-rendered**, the first Skia-drawn IDE text on Windows: means 27–33 light 2×, 15–20 dark 2×,
39–44 light 1× — lighter than FreeType, as T1 and the sheets. Not rendering: the Settings dialog at 100 % sits ≈ 130 px
elsewhere in R0/R5 than in A0 (per-arm IDE state), R5 another ≈ 13 px from R0 (possibly the Skia scaler's advances in
the dialog layout — unverified). Captures for the catalog: editor and settings, light and dark, both scales, in the tree.

**Not measured: frame times** — see ask 1.

## 5. Open

- **`ClipShapeTest` dashed modes under the raster flag.** The test renders each random path with Marlin's runtime
  clipping on and off and wants the two equal within 32 levels on ≤ 4 px. Under the flag a dashed stroke is Marlin's
  clipped outline filled by Skia (`SKRasterShapePipe`, dash branch). Its 116 saved pairs: 7276 differing px, 294 beyond
  32, max 191, in 77 pairs, on dash edges across the whole image (different edge coverage, not shifted dashes). **Not
  reproduced by `DashClipProbe`** (same stroke — width 10, dashes 13/7, all caps/joins, AA, STROKE_PURE — nine-line
  polylines in the test's coordinate range, 400 paths): clip on vs off identical to the pixel; raster vs loops differs by
  edge coverage only (max 216, images alike). What the test does that the probe does not: `clearRect` with AA off on a
  long-lived Graphics2D between paths, `KEY_RENDERING = QUALITY`, 5000 paths per setup. Next: run the test's own loop
  under the flag and dump one failing pair's outline paths. No red `SkiaRaster*Test` yet, so no fix — I start on this
  unless you say otherwise.
- The whole-pixel sheet parity (fm off, mean 50 vs native DirectWrite) and strike invalidation on ClearType settings
  changes, both from earlier rounds, remain open on the VM side.

## 6. Asks

1. **The paint-time instrument.** §4 of your hand-over wants IDE frame times "from the paint-time instrument"; nothing
   in the kit measures paint time on this side, and the ledger's paint-time numbers came from your own instrumentation
   in the ultimate repository. Either send the tool (and how it is attached to the isolated IDE), or confirm that the
   portable subset is acceptable: start-up to first frame and a Robot scroll/typing loop, 5 interleaved runs per arm.
2. **Review of the two code commits** that touch your design: the BGRA split in `SKRaster.cpp` (`58eec3997377`) and
   the text box in `SKRasterTextPipe` (`f5846f822d83`). Points to check: a BGRA build's `SkColor` is Java's ARGB int
   bit for bit (that is what `swizzled()` now relies on); the glyph-list bounds come from the strike's images, and Skia's
   rendering of the same glyph may differ by a pixel (I pad by 2); a glyph list with no images inside the clip now draws
   nothing.
3. **macOS re-measurement** of `SkiaRasterTextTest`'s LCD row on the opaque image (`bcb7b1e85bd6`), and the
   `MTLSKPlatform.mm` items still open from 2026-10-26 (CoreText synthetic-style measurements for the stub).
4. **Reword the `…-windows9` metrics-test commit** when folding it in: the cause is embedded bitmap strikes, not the head
   flag (the ledger entry of 2026-10-28 13:40 has the measured story).
5. **A decision on the slow RenderPerfTest rows** (§3) before the raster flag goes anywhere near a default on Windows:
   small AA boxes, thin AA lines and the non-AA shapes are the IDE's bread and butter, and they are 2–5× slower here on
   the mirror path.

## 7. Added 2026-10-29: LCD text on the GPU pipe's Skia text path — fixed, please review

`78398bf0f7bb` on `…-windows10`: `SKBackend_GetSurface` wrapped the window texture without surface properties, so Skia
downgraded every LCD request on the text path to grayscale before Graphite's LCD step. On macOS invisible (no sub-pixel
masks); on Windows the full Skia text stack lost ClearType the moment composite strikes joined the path (`24fbe9a05076`).
Now `SkSurfaceProps(0, kRGB_H)`; red/green `SkiaTextPathLcdTest`; the paragraph-route LCD sheet 0 → 33939 fringe
pixels. Review points: the geometry is fixed to RGB_H (Java's HBGR hint is not mapped); the two-pass LCD scratch
surface (`SKBackend.cpp` ~1724) still has default props, which is right for the glyph-image blend but worth a look if
the text path ever draws into it. Your §13.1 item "the Skia text path's LCD request is grayscale on Graphite" closes on
Windows with this. Also fixed: `LcdGlyphSourceTest` lacked `sun.font:+open` for real jtreg.
- Added 12:10: the geometry follows the display's order now — new platform hook `SKPlatform_LcdOrderIsBGR()` (**blind edit in `MTLSKPlatform.mm`, returns false**), `SKBackend_Configure` has one more parameter (`SKLcdOrder`, from `-Dsun.java2d.skia.lcdOrder=system|rgb|bgr`), `SKBackend_LcdPixelGeometry()` is shared by the GPU and raster surfaces. Please compile-check the Mac side. The text-path arms' LCD sheets in the Windows tree of 2026-10-27 are grayscale and stale; I re-render them before the next catalog build.

