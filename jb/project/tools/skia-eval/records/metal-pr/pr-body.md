The Metal glyph caches were freed and rebuilt far more often than their capacity required, each time with a GPU wait on the render-queue thread. Two changes, plus tests:

**JBR-7462** (Laurent Bourgès, extracted from `lbourges/JBR-7462-main-V2`): destination changes (`SET_SURFACES`, `SET_SCRATCH_SURFACE`, `DISPOSE_SURFACE`) flush the pending glyph quads instead of freeing both caches; the flush no longer commits or waits; the cache texture comes from the texture pool and is handed to the current command buffer before release, so it is reused only after that buffer has completed.

**JBR-10667**: a full cache evicts cells one at a time, as `AccelGlyphCache` does, instead of being replaced. Because the Metal cache is written from the CPU with `replaceRegion`, a cell is reused only once the command buffer that last drew it has completed; command buffers are numbered in commit order for that. If a single frame needs more distinct glyphs than there are cells, the previous replacement remains as the fallback. The caches grow from 512² to 1024² and the pooled A8 textures use a plain layout, so `replaceRegion` uploads are plain copies.

**Correctness**: pixel-identical on a 1152-cell comparison of software, Metal and Skia output and on a glyph-churn scene (3,000 distinct glyphs over 40 frames, grayscale and LCD). `jb/java/awt/Graphics2D`, `java/awt/font` and `sun/java2d` show no new failures.

**Measurements** (M1 Max). IntelliJ integration performance tests on JBR 25, seconds per run in `RepaintManager.paintDirtyRegions` / `MTLRenderQueue.flushNow`, medians:

| scene | before | after |
|---|---|---|
| typing (Kotlin) | 15.7 / 8.7 | 9.4 / 3.6 |
| settings, popups, tab strip | 15.2 / 7.3 | 12.7 / 4.7 |

Frame time of a text scene with N distinct glyphs per frame, median, gray / LCD:

| N | before | after |
|---|---|---|
| 600 | 6.3 / 6.9 ms | 5.1 / 5.6 ms |
| 1500 | 7.8 / 9.7 ms | 5.5 / 6.6 ms |

`GlyphCacheSurfaceSwitchTest`: 0.045 ms per surface switch, 0.415 ms before.

**Tests**: `GlyphCacheSurfaceSwitchTest` (Laurent), `GlyphCacheEvictionTest`, `GlyphCacheSubpixelTest`, `GlyphCacheFallbackTest`, `GlyphCacheDisposeTest`, all consistency checks of the pipeline against itself before and after cache churn.
