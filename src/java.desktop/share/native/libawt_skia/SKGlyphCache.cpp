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
#include "SKStats.h"
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
#include <memory>
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

    std::unique_ptr<SKQuadBatch> batch = std::make_unique<SKQuadBatch>();   // the open batch (two passes: of the current colour)
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
    // Two passes (LCD twopass): the batches of other colours, kept open beside `batch` on its
    // canvas, atlas and passes instead of being drawn at every colour change. No quad of an open
    // batch overlaps a quad of another open batch of a different colour (a sprite that would
    // first draws the batch it overlaps, see drawOverlapped), so the order in which open batches
    // are drawn does not matter, and glyphs of one colour commute under the two passes. A line of
    // syntax-coloured tokens is then a draw or two per colour instead of one per token.
    std::vector<std::unique_ptr<SKQuadBatch>> parked;
    std::vector<std::unique_ptr<SKQuadBatch>> spare;   // drawn batches, kept for their vectors
};

static const size_t MAX_PARKED = 15;

static void uploadDirtyRows(SKGlyphCache *gc);

// A closed batch (a new one gets the hooks of the cache's own).
static std::unique_ptr<SKQuadBatch> spareBatch(SKGlyphCache *gc) {
    std::unique_ptr<SKQuadBatch> b;
    if (!gc->spare.empty()) {
        b = std::move(gc->spare.back());
        gc->spare.pop_back();
    } else {
        b = std::make_unique<SKQuadBatch>();   // a spare keeps the hooks it was made with
        b->beforeUpload = gc->batch->beforeUpload;
        b->beforeDraw = gc->batch->beforeDraw;
        b->afterDraw = gc->batch->afterDraw;
    }
    return b;
}

// Draws a parked batch and keeps it as a spare.
static void retire(SKGlyphCache *gc, size_t i) {
    std::unique_ptr<SKQuadBatch> b = std::move(gc->parked[i]);
    gc->parked.erase(gc->parked.begin() + (std::ptrdiff_t) i);
    b->flush();
    gc->spare.push_back(std::move(b));
}

// Draws the cache's batches: its own and the parked ones (in any order, see `parked`).
static void drawBatches(SKGlyphCache *gc) {
    gc->batch->flush();
    while (!gc->parked.empty()) {
        retire(gc, gc->parked.size() - 1);
    }
}

// Two passes, a glyph list of this paint begins: the open batch of another colour goes aside and
// the paint's own, if one is parked, comes back. Batches of another canvas, atlas or contrast are
// drawn first: the parked ones always share the current target.
static void park(SKGlyphCache *gc, SkCanvas *canvas, const SkPaint &paint, const SKQuadBatchTwoPass *passes) {
    const bool open = gc->batch->pending();
    if ((open && !gc->batch->sameTarget(canvas, gc->atlasImage, passes))
            || (!gc->parked.empty() && !gc->parked.front()->sameTarget(canvas, gc->atlasImage, passes))) {
        drawBatches(gc);
        return;
    }
    if (open && gc->batch->samePaint(paint)) {
        return;   // the same colour continues
    }
    std::unique_ptr<SKQuadBatch> resumed;
    for (size_t i = 0; i < gc->parked.size(); i++) {
        if (gc->parked[i]->samePaint(paint)) {
            resumed = std::move(gc->parked[i]);
            gc->parked.erase(gc->parked.begin() + (std::ptrdiff_t) i);
            break;
        }
    }
    if (open) {
        if (gc->parked.size() >= MAX_PARKED) {
            retire(gc, 0);   // the oldest colour is drawn
        }
        std::unique_ptr<SKQuadBatch> aside = spareBatch(gc);
        std::swap(aside, gc->batch);   // `aside` holds the open batch, the cache a closed one
        gc->parked.push_back(std::move(aside));
    }
    if (resumed) {
        std::swap(resumed, gc->batch);
        resumed->flush();   // the closed one: nothing to draw
        gc->spare.push_back(std::move(resumed));
    }
}

// Before a sprite with this overlap extent joins the batch of `gc`: every open batch of another
// colour that it overlaps, parked here or open in another size bucket of the context, is drawn
// first, so the sprite composes over it as it was given.
static void drawOverlapped(SKGlyphCache *gc, const SkRect &extent) {
    for (size_t i = gc->parked.size(); i-- > 0; ) {
        if (gc->parked[i]->overlaps(extent)) {
            SKStats_Count(SK_COUNT_GLYPH_OVERLAP_DRAWS);
            retire(gc, i);
        }
    }
    for (int b = 0; b < SK_GLYPH_BUCKETS; b++) {
        SKGlyphCache *other = gc->lcd ? gc->cc->glyphCachesLCD[b] : gc->cc->glyphCachesAA[b];
        if (other == nullptr || other == gc) {
            continue;
        }
        if (other->batch->pending() && other->batch->overlaps(extent) && !other->batch->samePaint(gc->paint)) {
            SKStats_Count(SK_COUNT_GLYPH_OVERLAP_DRAWS);
            other->batch->flush();
        }
        for (size_t i = other->parked.size(); i-- > 0; ) {
            if (other->parked[i]->overlaps(extent) && !other->parked[i]->samePaint(gc->paint)) {
                SKStats_Count(SK_COUNT_GLYPH_OVERLAP_DRAWS);
                retire(other, i);
            }
        }
    }
}

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
    drawBatches(gAdding);
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
    // runs whenever a batch holding quads draws, from whichever path: the batch draw statistic counts here
    gc->batch->beforeUpload = [gc]() { SKGlyphCache_CountStat(SK_GLYPH_BATCH_DRAWS); uploadDirtyRows(gc); };
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
    if (passes != nullptr) {
        park(gc, canvas, paint, passes);
    }
    gc->batch->begin(canvas, paint, gc->atlasImage, passes);
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
                SKStats_Count(SK_COUNT_ATLAS_UPLOADS);
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
        drawBatches(gc);
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
    if (!gc->batch->active()) {
        gc->batch->begin(gc->canvas, gc->paint, gc->atlasImage, gc->passes);
    }
    if (gc->passes != nullptr) {
        const SkIRect &ink = gc->ink[cellIndex(gc, cell)];
        drawOverlapped(gc, SkRect::MakeLTRB(x + ink.fLeft, y + ink.fTop, x + ink.fRight, y + ink.fBottom));
    }
    gc->batch->addSprite(x, y, glyph->width, glyph->height, cell->x, cell->y, &gc->ink[cellIndex(gc, cell)]);
    return true;
}

void SKGlyphCache_Flush(SKGlyphCache *gc) {
    if (gc != nullptr) {
        drawBatches(gc);
    }
}

SKQuadBatch &SKGlyphCache_Batch(SKGlyphCache *gc) {
    return *gc->batch;
}
