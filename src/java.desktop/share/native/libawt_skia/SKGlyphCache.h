/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#ifndef SKGlyphCache_h_Included
#define SKGlyphCache_h_Included

#include "SKQuadBatch.h"

#include "jni.h"
#include "fontscalerdefs.h"
#include "AccelGlyphCache.h"

#include "include/core/SkBitmap.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkRect.h"
#include "include/core/SkSurface.h"

#include <array>
#include <memory>
#include <vector>

class SKRecordingOwner;
struct SKGlyphCacheSet;

// The caches come in size buckets: one cache per cell size, a glyph image going
// to the smallest cell it fits (SKGlyphCache::bucketFor). One cell size for all
// glyphs either wastes most of a 32-pixel cell on 1x UI text or sends every
// glyph above 16 pt on a 2x display through an upload and a draw of its own
// each frame. A run's glyphs mostly share a bucket;
// the buckets' batches stay open side by side through a glyph list.
constexpr int SK_GLYPH_BUCKETS = 4;
constexpr std::array<jint, SK_GLYPH_BUCKETS> SK_GLYPH_BUCKET_CELLS = {16, 32, 64, 128};
// SKGlyphCache::bucketFor's result for a glyph image that exceeds the largest cell.
constexpr int SK_GLYPH_NO_BUCKET = -1;

// An LCD glyph image row has 3 bytes of coverage per pixel (in the order the strike asked for); the atlases and the
// uncached LCD draw hold the coverage in the RGB channels of opaque RGBA texels.
constexpr int SK_LCD_COVERAGE_BYTES_PER_PIXEL = 3;
constexpr int SK_RGBA_BYTES_PER_PIXEL = 4;

// The caches' work so far, for diagnostics and tests (SKEnvironment.glyphCacheCounters):
// glyphs drawn from a cell, cells staged (first draw of a glyph image), cells evicted
// (a staged glyph replaced a cached one), atlas row uploads, glyphs drawn uncached by
// the backend (did not fit a cell, or no cell free), batch draws (a batch holding quads drawn: two
// passes count once). Written by the flusher, read anywhere. (An unscoped enum: the values index
// the counter array and the Java side reads them by position.)
enum SKGlyphCacheCounter { SK_GLYPHS_CACHED = 0, SK_CELLS_STAGED, SK_CELLS_EVICTED, SK_ATLAS_UPLOADS, SK_GLYPHS_UNCACHED,
                           SK_GLYPH_BATCH_DRAWS, SK_GLYPH_CACHE_COUNTERS };

// Glyph cache of one Graphite context: the shared Java2D AccelGlyphCache for
// cell bookkeeping and invalidation (sunFont.c evicts glyphs from it when they
// are freed), an SkSurface as the atlas texture, and a sprite batch drawn as
// one mesh (SKQuadBatch). Grayscale caches are alpha-only and take the paint
// color; LCD caches hold RGB coverage and are composited through the paint's
// blender, in two passes, or accumulated first (see SKTextPipe::drawGlyphList).
// The batch's observer: the staged rows are uploaded before the batch draws, and
// the draws are passed on to the cache's own observer (observeDraws).
class SKGlyphCache : private SKQuadBatchObserver {
public:
    // recordingOwner: the context the cache belongs to; siblings: the caches of the other buckets of the kind
    // (two-pass batches overlapping one of theirs draw theirs first); cellSize: the bucket's cell; side: the
    // atlas texture's side in texels (a multiple of cellSize). Null if the atlas, its image, its shadow or the
    // cell table cannot be made.
    [[nodiscard]] static std::unique_ptr<SKGlyphCache> make(SKRecordingOwner *recordingOwner, const SKGlyphCacheSet *siblings,
                                                          bool lcd, jint cellSize, jint side);
    ~SKGlyphCache();
    SKGlyphCache(const SKGlyphCache &) = delete;
    SKGlyphCache &operator=(const SKGlyphCache &) = delete;

    // The bucket for a glyph image, or SK_GLYPH_NO_BUCKET if it exceeds the largest cell.
    [[nodiscard]] static int bucketFor(const GlyphInfo *glyph) {
        const jint extent = glyph->width > glyph->height ? glyph->width : glyph->height;
        for (int bucket = 0; bucket < SK_GLYPH_BUCKETS; bucket++) {
            if (extent <= SK_GLYPH_BUCKET_CELLS[bucket]) {
                return bucket;
            }
        }
        return SK_GLYPH_NO_BUCKET;
    }

    // An LCD glyph image row (3 bytes of coverage per pixel, in the order the strike asked for) expanded
    // to opaque RGBA texels: the atlases and the uncached LCD draw hold the coverage in the RGB channels.
    static void expandLCDRow(const unsigned char *coverage, unsigned char *rgba, int width, jboolean rgbOrder) {
        for (int column = 0; column < width;
             column++, coverage += SK_LCD_COVERAGE_BYTES_PER_PIXEL, rgba += SK_RGBA_BYTES_PER_PIXEL) {
            rgba[0] = rgbOrder ? coverage[0] : coverage[2];
            rgba[1] = coverage[1];
            rgba[2] = rgbOrder ? coverage[2] : coverage[0];
            rgba[3] = 0xff;
        }
    }

    // A glyph list starts: the canvas and paint its glyphs are drawn with. Sprites
    // of consecutive glyph lists with the same canvas and paint accumulate in one
    // batch; a different paint draws the previous batch first. A flush inside the
    // list (an uncached glyph, or an eviction with no cell free of this recording)
    // closes the batch; the next cached glyph of the list reopens it.
    void begin(SkCanvas *canvas, const SkPaint &paint, const SKQuadBatchTwoPass *passes = nullptr);

    // Queues one glyph (sub)image at device position x, y; writes it into a cell
    // of the shadow bitmap first if it is not cached yet (uploaded with the next
    // batch draw). Returns false if no cell could be allocated.
    [[nodiscard]] bool draw(GlyphInfo *glyph, jint subimage, jboolean rgbOrder, float x, float y);

    // Draws the queued sprites as one mesh. The backend calls this before any
    // other draw, state or destination change, and before ending a recording.
    void flush();

    // What else runs around the draws of the cache's batch (the LCD coverage accumulation's clear and composite,
    // SKTextPipe): its beforeDraw and afterDraw, after the cache's upload (SKQuadBatchObserver); null: nothing.
    void observeDraws(SKQuadBatchObserver *drawObserver) { m_drawObserver = drawObserver; }

    static void count(SKGlyphCacheCounter counter, jlong amount = 1);
    [[nodiscard]] static jlong counter(SKGlyphCacheCounter counter);

private:
    SKGlyphCache();

    // AccelGlyphCache's flush callback (it takes no context): the cache adding a glyph draws its batch and ends
    // the recording.
    static void flushForEviction();

    // SKQuadBatchObserver: the batch draw is counted and the dirty rows go up; the rest is the draw observer's.
    void beforeUpload() override;
    void beforeDraw(const SkRect &bounds) override;
    void afterDraw(const SkRect &bounds, const SkPaint &paint) override;
    [[nodiscard]] bool takesDrawBounds() const override;

    void drawBatches();
    void drawOverlapped(const SkRect &extent);
    [[nodiscard]] size_t cellIndex(const CacheCellInfo *cell) const;
    void uploadDirtyRows();
    void markDirty(const CacheCellInfo *cell, int width, int height);
    void stageGlyph(const GlyphInfo *glyph, const CacheCellInfo *cell, jint subimage, jboolean rgbOrder);
    [[nodiscard]] CacheCellInfo *reusableCell(GlyphInfo *glyph);

    SKRecordingOwner *m_recordingOwner = nullptr;
    const SKGlyphCacheSet *m_siblings = nullptr;   // the set this cache is one of
    bool m_lcd = false;
    jint m_cellSize = 0;       // the bucket's cell size (make sets the geometry)
    jint m_atlasSide = 0;      // the atlas texture's side
    jint m_cellsPerRow = 0;    // m_atlasSide / m_cellSize
    GlyphCacheInfo *m_cellTable = nullptr;   // the shared AccelGlyphCache's cells
    sk_sp<SkSurface> m_atlas;
    sk_sp<SkImage> m_atlasImage;

    std::unique_ptr<SKQuadBatch> m_spriteBatch;   // the open batch (two passes: of the current color)
    SKQuadBatchObserver *m_drawObserver = nullptr;   // observeDraws
    // The glyph list being drawn: a flush inside the list (an uncached glyph,
    // or an eviction with no cell free of this recording) closes the batch,
    // draw reopens it.
    SkCanvas *m_canvas = nullptr;
    SkPaint m_paint;
    const SKQuadBatchTwoPass *m_passes = nullptr;
    // Per cell, the part of the glyph image above INK_THRESHOLD, relative to
    // the glyph's origin: what SKQuadBatch tests for overlap. The excluded
    // pixels are the LCD filter's tails; mesh-sharing glyphs that meet there
    // err by their product, measured as no difference against a threshold of
    // zero at the highest LCD contrast (LCDTextBackgroundsTest).
    std::vector<SkIRect> m_ink;
    // Per cell, the recording generation (SKRecordingOwner::recordingGeneration)
    // of the last sprite that sampled it. A cell last used in an earlier,
    // already inserted recording can be overwritten without a flush: Graphite
    // orders the recordings, and an upload in the next one follows every draw
    // of the previous. Only a cell used in the current recording needs the
    // recording ended first (the upload would precede the draws in it).
    std::vector<uint32_t> m_lastUsed;
    // The atlas' pixels on the CPU: glyphs are written here, and the rows
    // touched since the last draw go up in one writePixels each when the
    // batch draws (beforeUpload, SKQuadBatchObserver), instead of one upload per glyph.
    SkBitmap m_shadow;
    std::vector<SkIRect> m_dirtyRows;   // per cell row, the pixels to upload
};

// The glyph caches of one kind (grayscale or LCD) of a context, one per size bucket
// (SK_GLYPH_BUCKET_CELLS), each created on first use; a failed creation is not retried.
struct SKGlyphCacheSet {
    std::array<std::unique_ptr<SKGlyphCache>, SK_GLYPH_BUCKETS> caches;
    std::array<bool, SK_GLYPH_BUCKETS> failed = {};
};

#endif /* SKGlyphCache_h_Included */
