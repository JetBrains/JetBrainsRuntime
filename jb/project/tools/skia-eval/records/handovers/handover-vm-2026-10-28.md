# Hand-over to the Windows machines (VM and x64) — 2026-10-28: the metrics census, and the raster slice to come

Shared branch `origin/manuel.unterhofer/JBR-10654-skia-backend`; pull first (the x64 series is in it since 2026-10-27,
tip `b41016bc4ec6` plus today's commits).

## 1. Run the metrics census (chunk 2 of the long tail: text metrics on Skia)

`jb/project/tools/skia-eval/text-quality/probes/MetricsCensus.java` compares today's scaler's strike metrics and glyph
advances with the Skia font scaler's, over real fonts × sizes 8–32 × scales 1 / 1.25 / 1.5 / 2 × whole-pixel / fractional,
190 characters each, and prints one summary line per font (worst metric drift in device px, advances differing, whole-pixel
flips, sample-string width drift). On macOS every font is a clean zero (the Skia scaler on the CoreText host is the
platform's metrics). On Windows the hosts differ — FreeType today, Skia's DirectWrite host — and the x64 machine already saw
`Monospaced 10pt ×1.25: ascent −8.33 (Skia) vs −8.66`, so this is where work package 4's re-flow question lives.

    <image>/bin/javac --add-exports java.desktop/sun.font=ALL-UNNAMED --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED -d out MetricsCensus.java
    <image>/bin/java -Djava.awt.headless=true -Dsun.java2d.uiScale=1 --add-opens java.desktop/sun.font=ALL-UNNAMED \
        --add-exports java.desktop/sun.font=ALL-UNNAMED --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED -cp out MetricsCensus > census-win.txt

Default families on Windows: Inter, JetBrains Mono, Segoe UI, Consolas, Arial, Times New Roman (pass others as arguments).
Record the `##` summary lines and the worst rows in your raw records; if a font's drift is a rounding rule rather than a
metric (the FreeType hinted advance against the linear one, see `SkiaFontScalerMetricsTest`'s Windows branch), say which.

## 2. Coming: Skia as the software pipeline (chunk 1)

`sun.java2d.skia.raster=true` is landing on the shared branch: INT_ARGB / INT_ARGB_PRE / INT_RGB BufferedImages get an
`SKRasterSurfaceData` (a `BufImgSurfaceData`; the loops stay for everything the raster pipes do not take), whose antialiased
shapes Skia's raster backend draws into the image's own pixels on the calling thread — no GPU, no queue, headless included.
First slice: AA fills and strokes with a solid colour and a Porter-Duff composite; test `SkiaRasterSurfaceTest`. Windows is
the platform that ships on the loops, so once it is in: run the test there, and RenderPerfTest's `-e=buffered`-style arms
plus the IDE scenes with `-Dsun.java2d.skia=false -Dsun.java2d.skia.raster=true` against A0 — the first Skia-raster-vs-loops
numbers. Nothing to do until the commit is announced in the ledger (§12, 2026-10-28).
