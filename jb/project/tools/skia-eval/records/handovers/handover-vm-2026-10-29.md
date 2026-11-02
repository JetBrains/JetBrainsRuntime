# Hand-over to the Windows machines (VM and x64) — 2026-10-29: Skia as the software pipeline, verified on Windows

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
