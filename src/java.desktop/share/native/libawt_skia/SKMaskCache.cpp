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

#include "SKMaskCache.h"
#include "SKRecordingOwner.h"

#include "Trace.h"

#include <algorithm>
#include <cstring>

#include "include/core/SkPixmap.h"
#include "include/gpu/graphite/Surface.h"

namespace {

constexpr unsigned char FULL_COVERAGE = 0xff;

} // namespace

// Records the upload of the staged span. Must precede the draws that sample
// it: the atlas' pending uploads are only ordered ahead of a destination's
// draws that are recorded after them.
void SKMaskCache::uploadStaging() {
    if (m_stagingStart < m_stagingEnd) {
        SkPixmap span(SkImageInfo::MakeA8(m_stagingEnd - m_stagingStart, CELL_SIZE),
                      m_staging.data() + m_stagingStart, (size_t) CACHE_SIZE);
        m_atlas->writePixels(span, m_stagingStart, m_stagingRow);
        m_stagingStart = CACHE_SIZE;
        m_stagingEnd = 0;
    }
}

std::unique_ptr<SKMaskCache> SKMaskCache::make(SKRecordingOwner *recordingOwner) {
    if (recordingOwner == nullptr || recordingOwner->recorder() == nullptr) {
        return nullptr;
    }
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(recordingOwner->recorder(),
                                                      SkImageInfo::MakeA8(CACHE_SIZE, CACHE_SIZE));
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKMaskCache::make: cannot create the A8 atlas surface");
        return nullptr;
    }
    std::unique_ptr<SKMaskCache> cache(new SKMaskCache());
    cache->m_recordingOwner = recordingOwner;
    cache->m_atlas = std::move(atlas);
    cache->m_atlasImage = SkSurfaces::AsImage(cache->m_atlas);
    if (!cache->m_atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKMaskCache::make: cannot image the A8 atlas");
        return nullptr;
    }
    cache->m_staging.resize((size_t) CACHE_SIZE * CELL_SIZE);
    cache->m_batch.setObserver(cache.get());
    // the opaque cell for fully covered tiles (drawSolid)
    std::vector<unsigned char> opaque((size_t) CELL_SIZE * CELL_SIZE, FULL_COVERAGE);
    cache->m_atlas->writePixels(SkPixmap(SkImageInfo::MakeA8(CELL_SIZE, CELL_SIZE), opaque.data(), CELL_SIZE), 0, 0);
    cache->m_nextCell = FIRST_CELL;
    return cache;
}

bool SKMaskCache::fits(jint width, jint height) {
    return width <= CELL_SIZE && height <= CELL_SIZE;
}

// Next free cell; wrapping around overwrites cells that this recording's draws
// may still sample, so the batch is drawn and the recording ended first.
void SKMaskCache::allocateCell(jint *cellX, jint *cellY) {
    if (m_nextCell == CELL_COUNT) {
        flush();
        m_recordingOwner->endRecording();
        m_nextCell = FIRST_CELL;
    }
    *cellX = (m_nextCell % CELLS_PER_ROW) * CELL_SIZE;
    *cellY = (m_nextCell / CELLS_PER_ROW) * CELL_SIZE;
    m_nextCell++;
}

void SKMaskCache::draw(SkCanvas *canvas, const SkPaint &paint, jint x, jint y, jint width, jint height,
                       const unsigned char *mask, jint maskScan) {
    jint cellX, cellY;
    allocateCell(&cellX, &cellY);
    if (cellY != m_stagingRow) {
        uploadStaging();
        m_stagingRow = cellY;
    }
    for (jint row = 0; row < height; row++) {
        memcpy(m_staging.data() + (size_t) row * CACHE_SIZE + cellX, mask + (size_t) row * maskScan, (size_t) width);
    }
    if (paint.getShader() == nullptr) {
        // alpha-only atlas in the mesh: the tiles mask the paint color. The
        // batch uploads the staged span before it draws (beforeDraw), also when
        // begin() flushes an older batch for a different paint.
        m_stagingStart = std::min(m_stagingStart, cellX);
        m_stagingEnd = std::max(m_stagingEnd, cellX + width);
        m_batch.begin(canvas, paint, m_atlasImage);
        m_batch.addSprite(x, y, width, height, cellX, cellY);
        return;
    }
    // A gradient or texture paint has to be sampled in canvas coordinates, which
    // a mesh with atlas texture coordinates cannot express without sk_FragCoord
    // (private SkSL in m156). Draw such tiles one by one: an alpha-only image
    // masks the paint's shader. The tile is uploaded on its own so the draw can
    // sample it right away; its copy in the staging row keeps a later row upload
    // from overwriting the cell with stale data.
    m_batch.flush();
    SkPixmap tile(SkImageInfo::MakeA8(width, height), mask, (size_t) maskScan);
    m_atlas->writePixels(tile, cellX, cellY);
    canvas->drawImageRect(m_atlasImage.get(), SkRect::MakeXYWH(cellX, cellY, width, height),
                          SkRect::MakeXYWH(x, y, width, height),
                          SkSamplingOptions(SkFilterMode::kNearest), &paint, SkCanvas::kStrict_SrcRectConstraint);
}

void SKMaskCache::drawSolid(SkCanvas *canvas, const SkPaint &paint, jint x, jint y, jint width, jint height) {
    m_batch.begin(canvas, paint, m_atlasImage);
    m_batch.addSprite(x, y, width, height, OPAQUE_CELL * CELL_SIZE, OPAQUE_CELL * CELL_SIZE);
}

void SKMaskCache::flush() {
    uploadStaging();
    m_batch.flush();
}
