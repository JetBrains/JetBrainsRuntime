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

#include "SKGlyphCache.h"
#include "SKBackend.h"
#include "SKQuadBatch.h"

#include "AccelGlyphCache.h"
#include "Trace.h"

#include "include/core/SkBitmap.h"
#include "include/core/SkImage.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <vector>

// Cell geometry as MTLTextRenderer's caches, per bucket (SK_GLYPH_BUCKET_CELLS); the
// side comes from the backend's configuration (SKEnvironment.glyphCacheTexels: 1024
// for the 32-pixel bucket, 2048 under per-luminance strikes; sun.java2d.skia.glyphCache).
// Coverage up to this is the LCD filter's spill into the padding, not ink: it
// does not count for the overlap test of two glyphs.
static const unsigned char INK_THRESHOLD = 8;
// A cell rendered this often is kept over an idle one, as AccelGlyphCache_AddGlyph does.
static const jint TIMES_RENDERED_THRESHOLD = 5;

struct SKGlyphCache {
    SKConfigContext *cc = nullptr;
    bool lcd = false;
    jint cell = 32;          // the bucket's cell size
    jint side = 1024;        // the atlas texture's side
    jint cellsPerRow = 32;   // side / cell
    GlyphCacheInfo *info = nullptr;
    sk_sp<SkSurface> atlas;
    sk_sp<SkImage> atlasImage;

    SKQuadBatch batch;
    // The glyph list being drawn: a flush inside the list (an uncached glyph,
    // or an eviction with no cell free of this recording) closes the batch,
    // SKGlyphCache_Draw reopens it.
    SkCanvas *canvas = nullptr;
    SkPaint paint;
    const SKQuadBatchTwoPass *passes = nullptr;
    // Per cell, the part of the glyph image above INK_THRESHOLD, relative to
    // the glyph's origin: what SKQuadBatch tests for overlap. The excluded
    // pixels are the LCD filter's tails; mesh-sharing glyphs that meet there
    // err by their product, measured as no difference against a threshold of
    // zero at the highest LCD contrast (LcdTextBackgroundsTest).
    std::vector<SkIRect> ink;
    // Per cell, the recording generation (SKConfigContext::recordingGeneration)
    // of the last sprite that sampled it. A cell last used in an earlier,
    // already inserted recording can be overwritten without a flush: Graphite
    // orders the recordings, and an upload in the next one follows every draw
    // of the previous. Only a cell used in the current recording needs the
    // recording ended first (the upload would precede the draws in it).
    std::vector<uint32_t> lastUsed;
    // The atlas' pixels on the CPU: glyphs are written here, and the rows
    // touched since the last draw go up in one writePixels each when the
    // batch draws (SKQuadBatch::beforeUpload), instead of one upload per glyph.
    SkBitmap shadow;
    std::vector<SkIRect> dirtyRows;   // per cell row, the pixels to upload
};

static void uploadDirtyRows(SKGlyphCache *gc);

static std::atomic<jlong> gStats[SK_GLYPH_STAT_COUNT];

void SKGlyphCache_CountStat(SKGlyphCacheStat stat, jlong n) {
    gStats[stat].fetch_add(n, std::memory_order_relaxed);
}

jlong SKGlyphCache_Stat(SKGlyphCacheStat stat) {
    return gStats[stat].load(std::memory_order_relaxed);
}

static inline size_t cellIndex(const SKGlyphCache *gc, const CacheCellInfo *cell) {
    return (size_t) (cell->y / gc->cell) * gc->cellsPerRow + (size_t) (cell->x / gc->cell);
}

// Tight bounds of the pixels above the threshold; bytesPerPixel channels each.
static SkIRect inkBounds(const unsigned char *image, int w, int h, size_t rowBytes, int bytesPerPixel) {
    int left = w, top = h, right = 0, bottom = 0;
    for (int row = 0; row < h; row++) {
        const unsigned char *px = image + (size_t) row * rowBytes;
        for (int col = 0; col < w; col++, px += bytesPerPixel) {
            bool ink = false;
            for (int c = 0; c < bytesPerPixel; c++) {
                ink |= px[c] > INK_THRESHOLD;
            }
            if (ink) {
                left = std::min(left, col);
                right = std::max(right, col + 1);
                top = std::min(top, row);
                bottom = row + 1;
            }
        }
    }
    return left < right ? SkIRect::MakeLTRB(left, top, right, bottom) : SkIRect::MakeEmpty();
}

// AccelGlyphCache's flush callback takes no context: the cache that is adding
// a glyph (and may therefore evict) is the one being flushed.
static SKGlyphCache *gAdding = nullptr;

// Called by AccelGlyphCache_AddGlyph right before it overwrites an occupied
// cell, which only the call that fills the last free cell does; once the cache
// is full, SKGlyphCache_Draw evicts through reusableCell instead. Draw what
// refers to the old content, then end the recording: Graphite flushes the
// atlas' uploads ahead of the destination's pending draws, so an overwrite
// inside the same recording would be visible to the sprites queued before it.
static void flushForEviction() {
    if (gAdding == nullptr) {
        return;
    }
    gAdding->batch.flush();
    SKBackend_FlushRecording(gAdding->cc);
}

SKGlyphCache *SKGlyphCache_Create(SKConfigContext *cc, bool lcd, jint cellSize, jint side) {
    if (cc == nullptr || !cc->recorder || cellSize <= 0 || side < cellSize) {
        return nullptr;
    }
    side -= side % cellSize;
    SkImageInfo info = lcd ? SkImageInfo::Make(side, side, kRGBA_8888_SkColorType, kOpaque_SkAlphaType)
                           : SkImageInfo::MakeA8(side, side);
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(cc->recorder.get(), info);
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache_Create: cannot create a %s atlas surface", lcd ? "RGBA" : "A8");
        return nullptr;
    }
    auto *gc = new SKGlyphCache();
    gc->cc = cc;
    gc->lcd = lcd;
    gc->cell = cellSize;
    gc->side = side;
    gc->cellsPerRow = side / cellSize;
    gc->ink.resize((size_t) gc->cellsPerRow * gc->cellsPerRow, SkIRect::MakeEmpty());
    gc->lastUsed.resize((size_t) gc->cellsPerRow * gc->cellsPerRow, 0);
    gc->info = AccelGlyphCache_Init(side, side, cellSize, cellSize, flushForEviction);
    if (gc->info == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache_Create: cannot allocate the cell table");
        delete gc;
        return nullptr;
    }
    gc->atlas = std::move(atlas);
    gc->atlasImage = SkSurfaces::AsImage(gc->atlas);
    if (!gc->atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache_Create: cannot image the %s atlas", lcd ? "RGBA" : "A8");
        SKGlyphCache_Free(gc);
        return nullptr;
    }
    if (!gc->shadow.tryAllocPixels(info)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache_Create: cannot allocate the %s atlas shadow", lcd ? "RGBA" : "A8");
        SKGlyphCache_Free(gc);
        return nullptr;
    }
    gc->shadow.eraseColor(SK_ColorTRANSPARENT);
    gc->dirtyRows.resize((size_t) gc->cellsPerRow, SkIRect::MakeEmpty());
    gc->batch.beforeUpload = [gc]() { uploadDirtyRows(gc); };
    return gc;
}

void SKGlyphCache_Free(SKGlyphCache *gc) {
    if (gc == nullptr) {
        return;
    }
    if (gc->info != nullptr) {
        AccelGlyphCache_Free(gc->info);   // detaches the cells from their glyphs
    }
    delete gc;
}

bool SKGlyphCache_Fits(const SKGlyphCache *gc, const GlyphInfo *glyph) {
    return gc != nullptr && glyph->width <= gc->cell && glyph->height <= gc->cell;
}

void SKGlyphCache_Begin(SKGlyphCache *gc, SkCanvas *canvas, const SkPaint &paint, const SKQuadBatchTwoPass *passes) {
    gc->canvas = canvas;
    gc->paint = paint;
    gc->passes = passes;
    gc->batch.begin(canvas, paint, gc->atlasImage, passes);
}

// Uploads the rows of the shadow that changed since the last upload.
static void uploadDirtyRows(SKGlyphCache *gc) {
    SkPixmap all;
    if (!gc->shadow.peekPixels(&all)) {
        return;
    }
    for (SkIRect &dirty : gc->dirtyRows) {
        if (!dirty.isEmpty()) {
            SkPixmap sub;
            if (all.extractSubset(&sub, dirty)) {
                gc->atlas->writePixels(sub, dirty.fLeft, dirty.fTop);
                SKGlyphCache_CountStat(SK_ATLAS_UPLOADS);
            }
            dirty.setEmpty();
        }
    }
}

static void markDirty(SKGlyphCache *gc, const CacheCellInfo *cell, int w, int h) {
    gc->dirtyRows[(size_t) (cell->y / gc->cell)].join(SkIRect::MakeXYWH(cell->x, cell->y, w, h));
}

// Writes the glyph into its cell of the shadow bitmap; the row goes to the GPU
// with the next batch draw (uploadDirtyRows).
static void stageGlyph(SKGlyphCache *gc, const GlyphInfo *glyph, const CacheCellInfo *cell, jint subimage, jboolean rgbOrder) {
    const int w = glyph->width, h = glyph->height;
    if (!gc->lcd) {
        const unsigned char *data = glyph->image + (size_t) glyph->rowBytes * h * subimage;
        for (int row = 0; row < h; row++) {
            memcpy(gc->shadow.getAddr8(cell->x, cell->y + row), data + (size_t) row * glyph->rowBytes, (size_t) w);
        }
        gc->ink[cellIndex(gc, cell)] = inkBounds(data, w, h, glyph->rowBytes, 1);
        markDirty(gc, cell, w, h);
        return;
    }
    // LCD glyph: 3 bytes of coverage per pixel, in the order the strike asked for.
    const unsigned char *image = glyph->image + (size_t) glyph->rowBytes * h * subimage;
    gc->ink[cellIndex(gc, cell)] = inkBounds(image, w, h, glyph->rowBytes, 3);
    for (int row = 0; row < h; row++) {
        const unsigned char *src = image + (size_t) row * glyph->rowBytes;
        unsigned char *dst = (unsigned char *) gc->shadow.getAddr32(cell->x, cell->y + row);
        for (int col = 0; col < w; col++, src += 3, dst += 4) {
            dst[0] = rgbOrder ? src[0] : src[2];
            dst[1] = src[1];
            dst[2] = rgbOrder ? src[2] : src[0];
            dst[3] = 0xff;
        }
    }
    markDirty(gc, cell, w, h);
}

// A cell for `glyph` in a full cache, with AccelGlyphCache_AddGlyph's policy
// (walk from the head, keep cells rendered at least TIMES_RENDERED_THRESHOLD
// times, rotate the walked cells to the tail) and one more condition: the cell
// was last used in an earlier recording, so no flush is needed. If every cell
// was used in the current recording, the recording is ended once and the walk
// starts over; the eviction is then free for the rest of the recording.
static CacheCellInfo *reusableCell(SKGlyphCache *gc, GlyphInfo *glyph) {
    GlyphCacheInfo *info = gc->info;
    const size_t cells = gc->lastUsed.size();
    for (int pass = 0; pass < 2; pass++) {
        const uint32_t current = gc->cc->recordingGeneration;
        for (size_t i = 0; i < cells; i++) {
            CacheCellInfo *cell = info->head;
            const bool idle = gc->lastUsed[cellIndex(gc, cell)] < current;
            const bool reusable = cell->glyphInfo == NULL || (idle && cell->timesRendered < TIMES_RENDERED_THRESHOLD);
            info->head = cell->next;
            info->tail->next = cell;
            info->tail = cell;
            cell->next = NULL;
            cell->timesRendered = 0;
            if (reusable) {
                if (cell->glyphInfo != NULL) {
                    AccelGlyphCache_RemoveCellInfo(cell->glyphInfo, cell);
                    SKGlyphCache_CountStat(SK_CELLS_EVICTED);
                }
                cell->glyphInfo = glyph;
                cell->tx2 = cell->tx1 + ((jfloat) glyph->width / info->width);
                cell->ty2 = cell->ty1 + ((jfloat) glyph->height / info->height);
                AccelGlyphCache_AddCellInfo(glyph, cell);
                return cell;
            }
        }
        // every cell was used in this recording: end it, then the walk finds one
        gc->batch.flush();
        SKBackend_FlushRecording(gc->cc);
    }
    return nullptr;
}

bool SKGlyphCache_Draw(SKGlyphCache *gc, GlyphInfo *glyph, jint subimage, jboolean rgbOrder, float x, float y) {
    // Cached already? The glyph's cell chain interleaves the cells of all
    // caches; sub-pixel strikes keep one cell per sub-image.
    CacheCellInfo *cell = (CacheCellInfo *) glyph->cellInfo;
    while (cell != nullptr && !(cell->cacheInfo == gc->info && cell->glyphSubimage == subimage)) {
        cell = cell->nextGCI;
    }
    if (cell == nullptr) {
        if (gc->info->isFull) {
            cell = reusableCell(gc, glyph);
        } else {
            gAdding = gc;
            cell = AccelGlyphCache_AddGlyph(gc->info, glyph);
            gAdding = nullptr;
        }
        if (cell == nullptr) {
            return false;
        }
        cell->glyphSubimage = subimage;
        stageGlyph(gc, glyph, cell, subimage, rgbOrder);
        SKGlyphCache_CountStat(SK_CELLS_STAGED);
    }
    SKGlyphCache_CountStat(SK_GLYPHS_CACHED);
    cell->timesRendered++;
    gc->lastUsed[cellIndex(gc, cell)] = gc->cc->recordingGeneration;
    if (!gc->batch.active()) {
        gc->batch.begin(gc->canvas, gc->paint, gc->atlasImage, gc->passes);
    }
    gc->batch.addSprite(x, y, glyph->width, glyph->height, cell->x, cell->y, &gc->ink[cellIndex(gc, cell)]);
    return true;
}

void SKGlyphCache_Flush(SKGlyphCache *gc) {
    if (gc != nullptr) {
        gc->batch.flush();
    }
}

SKQuadBatch &SKGlyphCache_Batch(SKGlyphCache *gc) {
    return gc->batch;
}
