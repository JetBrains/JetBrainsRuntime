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
// ReSharper disable once CppUnusedIncludeDirective - memcpy is used here
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
SkIRect inkBounds(const unsigned char *image, const int width, const int height, const size_t rowBytes,
                  const int bytesPerPixel) {
    int left = width, top = height, right = 0, bottom = 0;
    for (int row = 0; row < height; row++) {
        const unsigned char *pixel = image + static_cast<size_t>(row) * rowBytes;
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
// ReSharper disable once CppMemberFunctionMayBeConst - draws and empties the sprite batch
void SKGlyphCache::drawBatches() {
    m_spriteBatch->flush();
}

// Two passes (LCD twoPass): the color is a uniform of the passes' shaders, so a batch holds one color and a color
// change draws it (SKQuadBatch::begin). Before a sprite with this overlap extent joins this cache's batch, the open
// batches of the context's other size buckets that it overlaps and that have another color are drawn first. The
// sprite then composes over them as it was given.
// ReSharper disable once CppMemberFunctionMayBeConst - draws the siblings' batches
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

void SKGlyphCache::count(const SKGlyphCacheCounter counter, const jlong amount) {
    gCounters[counter].fetch_add(amount, std::memory_order_relaxed);
}

jlong SKGlyphCache::counter(const SKGlyphCacheCounter counter) {
    return gCounters[counter].load(std::memory_order_relaxed);
}

size_t SKGlyphCache::cellIndex(const CacheCellInfo *cell) const {
    return static_cast<size_t>(cell->y / m_cellSize) * m_cellsPerRow + static_cast<size_t>(cell->x / m_cellSize);
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

std::unique_ptr<SKGlyphCache> SKGlyphCache::make(SKRecordingOwner *recordingOwner, const SKGlyphCacheSet *siblings,
                                                 const bool lcd, const jint cellSize, jint side) {
    if (recordingOwner == nullptr || recordingOwner->recorder() == nullptr || cellSize <= 0 || side < cellSize) {
        return nullptr;
    }
    side -= side % cellSize;
    const char *atlasFormat = lcd ? "RGBA" : "A8";
    const SkImageInfo info = lcd ? SkImageInfo::Make(side, side, kRGBA_8888_SkColorType, kOpaque_SkAlphaType)
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
    const size_t cellCount = static_cast<size_t>(cache->m_cellsPerRow) * cache->m_cellsPerRow;
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
    cache->m_dirtyRows.resize(static_cast<size_t>(cache->m_cellsPerRow), SkIRect::MakeEmpty());
    cache->m_spriteBatch->setObserver(cache.get());
    return cache;
}

// Runs whenever a batch holding quads draws, from whichever path: the batch draw statistic counts here.
void SKGlyphCache::beforeUpload() {
    count(SK_GLYPH_BATCH_DRAWS);
    uploadDirtyRows();
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

void SKGlyphCache::markDirty(const CacheCellInfo *cell, const int width, const int height) {
    m_dirtyRows[static_cast<size_t>(cell->y / m_cellSize)].join(SkIRect::MakeXYWH(cell->x, cell->y, width, height));
}

// Writes the glyph into its cell of the shadow bitmap; the row goes to the GPU
// with the next batch draw (uploadDirtyRows).
void SKGlyphCache::stageGlyph(const GlyphInfo *glyph, const CacheCellInfo *cell, const jint subimage,
                              const jboolean rgbOrder) {
    const int width = glyph->width, height = glyph->height;
    const unsigned char *image = glyph->image + static_cast<size_t>(glyph->rowBytes) * height * subimage;
    if (!m_lcd) {
        for (int row = 0; row < height; row++) {
            memcpy(m_shadow.getAddr8(cell->x, cell->y + row), image + static_cast<size_t>(row) * glyph->rowBytes,
                   static_cast<size_t>(width));
        }
        m_ink[cellIndex(cell)] = inkBounds(image, width, height, glyph->rowBytes, 1);
        markDirty(cell, width, height);
        return;
    }
    // LCD glyph: 3 bytes of coverage per pixel, expanded to the atlas' RGBA texels.
    m_ink[cellIndex(cell)] = inkBounds(image, width, height, glyph->rowBytes, SK_LCD_COVERAGE_BYTES_PER_PIXEL);
    for (int row = 0; row < height; row++) {
        expandLCDRow(image + static_cast<size_t>(row) * glyph->rowBytes,
                     static_cast<unsigned char *>(m_shadow.getAddr(cell->x, cell->y + row)), width, rgbOrder);
    }
    markDirty(cell, width, height);
}

// A cell for `glyph` in a full cache, with AccelGlyphCache_AddGlyph's policy
// (walk from the head, keep cells rendered at least TIMES_RENDERED_THRESHOLD
// times, rotate the walked cells to the tail) and one more condition: the cell
// was last used in an earlier recording, so no flush is needed. That holds for a
// freed cell too (its glyph's strike disposed, through the render queue): a sprite
// of the current recording may still sample it. If every cell was used in the
// current recording, the recording is ended once and the walk starts over; the
// eviction is then free for the rest of the recording.
CacheCellInfo *SKGlyphCache::reusableCell(GlyphInfo *glyph) {
    const size_t cellCount = m_lastUsed.size();
    for (int pass = 0; pass < REUSABLE_CELL_PASSES; pass++) {
        const uint32_t currentRecording = m_recordingOwner->recordingGeneration();
        for (size_t walked = 0; walked < cellCount; walked++) {
            CacheCellInfo *cell = m_cellTable->head;
            const bool idle = m_lastUsed[cellIndex(cell)] < currentRecording;
            const bool reusable = idle && (cell->glyphInfo == nullptr || cell->timesRendered < TIMES_RENDERED_THRESHOLD);
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
                cell->tx2 = cell->tx1 + static_cast<jfloat>(glyph->width) / static_cast<jfloat>(m_cellTable->width);
                cell->ty2 = cell->ty1 + static_cast<jfloat>(glyph->height) / static_cast<jfloat>(m_cellTable->height);
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

bool SKGlyphCache::draw(GlyphInfo *glyph, const jint subimage, const jboolean rgbOrder, const float x, const float y) {
    // Cached already? The glyph's cell chain interleaves the cells of all
    // caches; sub-pixel strikes keep one cell per sub-image.
    // The cell's key: the sub-image, and for LCD the order its texels were expanded in, as one strike serves
    // both orders (FontStrikeDesc: HBGR text takes the HRGB strike, the order applies when drawing).
    const jint key = m_lcd && !rgbOrder ? subimage | SK_GLYPH_BGR_ORDER_KEY : subimage;
    auto *cell = static_cast<CacheCellInfo *>(glyph->cellInfo);
    while (cell != nullptr && !(cell->cacheInfo == m_cellTable && cell->glyphSubimage == key)) {
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
        cell->glyphSubimage = key;
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
        drawOverlapped(SkRect::Make(glyphInk).makeOffset(x, y));
    }
    m_spriteBatch->addSprite(x, y, glyph->width, glyph->height, cell->x, cell->y, &glyphInk);
    return true;
}

void SKGlyphCache::flush() {
    drawBatches();
}
