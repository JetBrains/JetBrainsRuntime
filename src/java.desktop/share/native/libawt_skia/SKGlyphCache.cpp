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
#include "SKRecordingOwner.h"
#include "SKQuadBatch.h"

#include "Trace.h"

#include "include/core/SkPixmap.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>
#include <atomic>
#include <cstring>

namespace {

// Cell geometry as MTLTextRenderer's caches, per bucket (SK_GLYPH_BUCKET_CELLS); the
// side comes from the backend's configuration (SKEnvironment.GLYPH_CACHE_TEXELS: 1024
// for the 32-pixel bucket).
// Coverage up to this is the LCD filter's spill into the padding, not ink: it
// does not count for the overlap test of two glyphs.
constexpr unsigned char INK_THRESHOLD = 8;
// A cell rendered this often is kept over an idle one, as AccelGlyphCache_AddGlyph does.
constexpr jint TIMES_RENDERED_THRESHOLD = 5;
// reusableCell walks the cells twice at most: once as they are, once after ending the recording.
constexpr int REUSABLE_CELL_PASSES = 2;

std::atomic<jlong> gCounters[SK_GLYPH_CACHE_COUNTERS];

// AccelGlyphCache's flush callback takes no context: the cache that is adding
// a glyph (and may therefore evict) is the one being flushed.
SKGlyphCache *gAddingCache = nullptr;

// Tight bounds of the pixels above the threshold; bytesPerPixel channels each.
SkIRect inkBounds(const unsigned char *image, int width, int height, size_t rowBytes, int bytesPerPixel) {
    int left = width, top = height, right = 0, bottom = 0;
    for (int row = 0; row < height; row++) {
        const unsigned char *pixel = image + (size_t) row * rowBytes;
        for (int column = 0; column < width; column++, pixel += bytesPerPixel) {
            bool ink = false;
            for (int channel = 0; channel < bytesPerPixel; channel++) {
                ink |= pixel[channel] > INK_THRESHOLD;
            }
            if (ink) {
                left = std::min(left, column);
                right = std::max(right, column + 1);
                top = std::min(top, row);
                bottom = row + 1;
            }
        }
    }
    return left < right ? SkIRect::MakeLTRB(left, top, right, bottom) : SkIRect::MakeEmpty();
}

} // namespace

SKGlyphCache::SKGlyphCache() : m_spriteBatch(std::make_unique<SKQuadBatch>()) {}

// Draws the cache's batch.
void SKGlyphCache::drawBatches() {
    m_spriteBatch->flush();
}

// Two passes (LCD twoPass): the color is a uniform of the passes' shaders, so a batch holds one color and a color
// change draws it (SKQuadBatch::begin). Before a sprite with this overlap extent joins the batch of this cache, the
// open batch of another color in another size bucket of the context that it overlaps is drawn first, so the sprite
// composes over it as it was given.
void SKGlyphCache::drawOverlapped(const SkRect &extent) {
    for (const std::unique_ptr<SKGlyphCache> &other : m_siblings->caches) {
        if (other == nullptr || other.get() == this) {
            continue;
        }
        if (other->m_spriteBatch->pending() && other->m_spriteBatch->overlaps(extent) && !other->m_spriteBatch->samePaint(m_paint)) {
            other->m_spriteBatch->flush();
        }
    }
}

void SKGlyphCache::count(SKGlyphCacheCounter counter, jlong amount) {
    gCounters[counter].fetch_add(amount, std::memory_order_relaxed);
}

jlong SKGlyphCache::counter(SKGlyphCacheCounter counter) {
    return gCounters[counter].load(std::memory_order_relaxed);
}

size_t SKGlyphCache::cellIndex(const CacheCellInfo *cell) const {
    return (size_t) (cell->y / m_cellSize) * m_cellsPerRow + (size_t) (cell->x / m_cellSize);
}

// Called by AccelGlyphCache_AddGlyph right before it overwrites an occupied
// cell, which only the call that fills the last free cell does; once the cache
// is full, draw evicts through reusableCell instead. Draw what
// refers to the old content, then end the recording: Graphite flushes the
// atlas' uploads ahead of the destination's pending draws, so an overwrite
// inside the same recording would be visible to the sprites queued before it.
void SKGlyphCache::flushForEviction() {
    if (gAddingCache == nullptr) {
        return;
    }
    gAddingCache->drawBatches();
    gAddingCache->m_recordingOwner->endRecording();
}

std::unique_ptr<SKGlyphCache> SKGlyphCache::make(SKRecordingOwner *recordingOwner, const SKGlyphCacheSet *siblings, bool lcd,
                                                 jint cellSize, jint side) {
    if (recordingOwner == nullptr || recordingOwner->recorder() == nullptr || cellSize <= 0 || side < cellSize) {
        return nullptr;
    }
    side -= side % cellSize;
    const char *atlasFormat = lcd ? "RGBA" : "A8";
    SkImageInfo info = lcd ? SkImageInfo::Make(side, side, kRGBA_8888_SkColorType, kOpaque_SkAlphaType)
                           : SkImageInfo::MakeA8(side, side);
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(recordingOwner->recorder(), info);
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache::make: cannot create a %s atlas surface", atlasFormat);
        return nullptr;
    }
    std::unique_ptr<SKGlyphCache> cache(new SKGlyphCache());
    cache->m_recordingOwner = recordingOwner;
    cache->m_siblings = siblings;
    cache->m_lcd = lcd;
    cache->m_cellSize = cellSize;
    cache->m_atlasSide = side;
    cache->m_cellsPerRow = side / cellSize;
    const size_t cellCount = (size_t) cache->m_cellsPerRow * cache->m_cellsPerRow;
    cache->m_ink.resize(cellCount, SkIRect::MakeEmpty());
    cache->m_lastUsed.resize(cellCount, 0);
    cache->m_cellTable = AccelGlyphCache_Init(side, side, cellSize, cellSize, flushForEviction);
    if (cache->m_cellTable == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache::make: cannot allocate the cell table");
        return nullptr;
    }
    cache->m_atlas = std::move(atlas);
    cache->m_atlasImage = SkSurfaces::AsImage(cache->m_atlas);
    if (!cache->m_atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache::make: cannot image the %s atlas", atlasFormat);
        return nullptr;
    }
    if (!cache->m_shadow.tryAllocPixels(info)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache::make: cannot allocate the %s atlas shadow", atlasFormat);
        return nullptr;
    }
    cache->m_shadow.eraseColor(SK_ColorTRANSPARENT);
    cache->m_dirtyRows.resize((size_t) cache->m_cellsPerRow, SkIRect::MakeEmpty());
    cache->m_spriteBatch->setObserver(cache.get());
    return cache;
}

// Runs whenever a batch holding quads draws, from whichever path: the batch draw statistic counts here.
void SKGlyphCache::beforeUpload() {
    count(SK_GLYPH_BATCH_DRAWS);
    uploadDirtyRows();
}

void SKGlyphCache::beforeDraw(const SkRect &bounds) {
    if (m_drawObserver != nullptr) {
        m_drawObserver->beforeDraw(bounds);
    }
}

void SKGlyphCache::afterDraw(const SkRect &bounds, const SkPaint &paint) {
    if (m_drawObserver != nullptr) {
        m_drawObserver->afterDraw(bounds, paint);
    }
}

bool SKGlyphCache::takesDrawBounds() const {
    return m_drawObserver != nullptr && m_drawObserver->takesDrawBounds();
}

SKGlyphCache::~SKGlyphCache() {
    if (m_cellTable != nullptr) {
        AccelGlyphCache_Free(m_cellTable);   // detaches the cells from their glyphs
    }
}

void SKGlyphCache::begin(SkCanvas *canvas, const SkPaint &paint, const SKQuadBatchTwoPass *passes) {
    m_canvas = canvas;
    m_paint = paint;
    m_passes = passes;
    m_spriteBatch->begin(canvas, paint, m_atlasImage, passes);
}

// Uploads the rows of the shadow that changed since the last upload.
void SKGlyphCache::uploadDirtyRows() {
    SkPixmap shadowPixels;
    if (!m_shadow.peekPixels(&shadowPixels)) {
        return;
    }
    for (SkIRect &dirty : m_dirtyRows) {
        if (!dirty.isEmpty()) {
            SkPixmap dirtyPixels;
            if (shadowPixels.extractSubset(&dirtyPixels, dirty)) {
                m_atlas->writePixels(dirtyPixels, dirty.fLeft, dirty.fTop);
                count(SK_ATLAS_UPLOADS);
            }
            dirty.setEmpty();
        }
    }
}

void SKGlyphCache::markDirty(const CacheCellInfo *cell, int width, int height) {
    m_dirtyRows[(size_t) (cell->y / m_cellSize)].join(SkIRect::MakeXYWH(cell->x, cell->y, width, height));
}

// Writes the glyph into its cell of the shadow bitmap; the row goes to the GPU
// with the next batch draw (uploadDirtyRows).
void SKGlyphCache::stageGlyph(const GlyphInfo *glyph, const CacheCellInfo *cell, jint subimage, jboolean rgbOrder) {
    const int width = glyph->width, height = glyph->height;
    const unsigned char *image = glyph->image + (size_t) glyph->rowBytes * height * subimage;
    if (!m_lcd) {
        for (int row = 0; row < height; row++) {
            memcpy(m_shadow.getAddr8(cell->x, cell->y + row), image + (size_t) row * glyph->rowBytes, (size_t) width);
        }
        m_ink[cellIndex(cell)] = inkBounds(image, width, height, glyph->rowBytes, 1);
        markDirty(cell, width, height);
        return;
    }
    // LCD glyph: 3 bytes of coverage per pixel, expanded to the atlas' RGBA texels.
    m_ink[cellIndex(cell)] = inkBounds(image, width, height, glyph->rowBytes, SK_LCD_COVERAGE_BYTES_PER_PIXEL);
    for (int row = 0; row < height; row++) {
        expandLCDRow(image + (size_t) row * glyph->rowBytes,
                     (unsigned char *) m_shadow.getAddr32(cell->x, cell->y + row), width, rgbOrder);
    }
    markDirty(cell, width, height);
}

// A cell for `glyph` in a full cache, with AccelGlyphCache_AddGlyph's policy
// (walk from the head, keep cells rendered at least TIMES_RENDERED_THRESHOLD
// times, rotate the walked cells to the tail) and one more condition: the cell
// was last used in an earlier recording, so no flush is needed. If every cell
// was used in the current recording, the recording is ended once and the walk
// starts over; the eviction is then free for the rest of the recording.
CacheCellInfo *SKGlyphCache::reusableCell(GlyphInfo *glyph) {
    const size_t cellCount = m_lastUsed.size();
    for (int pass = 0; pass < REUSABLE_CELL_PASSES; pass++) {
        const uint32_t currentRecording = m_recordingOwner->recordingGeneration();
        for (size_t walked = 0; walked < cellCount; walked++) {
            CacheCellInfo *cell = m_cellTable->head;
            const bool idle = m_lastUsed[cellIndex(cell)] < currentRecording;
            const bool reusable = cell->glyphInfo == nullptr || (idle && cell->timesRendered < TIMES_RENDERED_THRESHOLD);
            m_cellTable->head = cell->next;
            m_cellTable->tail->next = cell;
            m_cellTable->tail = cell;
            cell->next = nullptr;
            cell->timesRendered = 0;
            if (reusable) {
                if (cell->glyphInfo != nullptr) {
                    AccelGlyphCache_RemoveCellInfo(cell->glyphInfo, cell);
                    count(SK_CELLS_EVICTED);
                }
                cell->glyphInfo = glyph;
                cell->tx2 = cell->tx1 + ((jfloat) glyph->width / m_cellTable->width);
                cell->ty2 = cell->ty1 + ((jfloat) glyph->height / m_cellTable->height);
                AccelGlyphCache_AddCellInfo(glyph, cell);
                return cell;
            }
        }
        // every cell was used in this recording: end it, then the walk finds one
        drawBatches();
        m_recordingOwner->endRecording();
    }
    return nullptr;
}

bool SKGlyphCache::draw(GlyphInfo *glyph, jint subimage, jboolean rgbOrder, float x, float y) {
    // Cached already? The glyph's cell chain interleaves the cells of all
    // caches; sub-pixel strikes keep one cell per sub-image.
    CacheCellInfo *cell = (CacheCellInfo *) glyph->cellInfo;
    while (cell != nullptr && !(cell->cacheInfo == m_cellTable && cell->glyphSubimage == subimage)) {
        cell = cell->nextGCI;
    }
    if (cell == nullptr) {
        if (m_cellTable->isFull) {
            cell = reusableCell(glyph);
        } else {
            gAddingCache = this;
            cell = AccelGlyphCache_AddGlyph(m_cellTable, glyph);
            gAddingCache = nullptr;
        }
        if (cell == nullptr) {
            return false;
        }
        cell->glyphSubimage = subimage;
        stageGlyph(glyph, cell, subimage, rgbOrder);
        count(SK_CELLS_STAGED);
    }
    count(SK_GLYPHS_CACHED);
    cell->timesRendered++;
    const size_t index = cellIndex(cell);
    m_lastUsed[index] = m_recordingOwner->recordingGeneration();
    if (!m_spriteBatch->active()) {
        m_spriteBatch->begin(m_canvas, m_paint, m_atlasImage, m_passes);
    }
    const SkIRect &glyphInk = m_ink[index];
    if (m_passes != nullptr) {
        drawOverlapped(SkRect::MakeLTRB(x + glyphInk.fLeft, y + glyphInk.fTop, x + glyphInk.fRight, y + glyphInk.fBottom));
    }
    m_spriteBatch->addSprite(x, y, glyph->width, glyph->height, cell->x, cell->y, &glyphInk);
    return true;
}

void SKGlyphCache::flush() {
    drawBatches();
}
