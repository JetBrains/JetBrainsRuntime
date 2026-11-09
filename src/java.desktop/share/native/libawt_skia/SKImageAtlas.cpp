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

#include "SKImageAtlas.h"
#include "SKRecordingOwner.h"
#include "SKSurfaceData.h"

#include "Trace.h"

#include <algorithm>
#include <atomic>

#include "include/gpu/graphite/Surface.h"

namespace {

constexpr jint ATLAS_SIZE = 2048;   // the atlas' side in texels
constexpr jint MAX_CELL = 128;      // larger sources are drawn on their own
constexpr jint GAP = 1;             // texels between cells (the sprites sample texel centers only)
constexpr jint SHELF_HEIGHT_STEP = 8;   // shelf heights are rounded up to this, so that sources of near heights share a shelf

std::atomic<jlong> gCounters[SK_IMAGE_ATLAS_COUNTERS];

} // namespace

std::unique_ptr<SKImageAtlas> SKImageAtlas::make(SKRecordingOwner *recordingOwner) {
    if (recordingOwner == nullptr || recordingOwner->recorder() == nullptr) {
        return nullptr;
    }
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(recordingOwner->recorder(),
            SkImageInfo::Make(ATLAS_SIZE, ATLAS_SIZE, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKImageAtlas::make: cannot create the atlas surface");
        return nullptr;
    }
    std::unique_ptr<SKImageAtlas> imageAtlas(new SKImageAtlas());
    imageAtlas->m_recordingOwner = recordingOwner;
    imageAtlas->m_atlas = std::move(atlas);
    imageAtlas->m_atlas->getCanvas()->clear(SK_ColorTRANSPARENT);
    imageAtlas->m_atlasImage = SkSurfaces::AsImage(imageAtlas->m_atlas);
    if (!imageAtlas->m_atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKImageAtlas::make: cannot image the atlas");
        return nullptr;
    }
    imageAtlas->m_batch.setObserver(imageAtlas.get());
    return imageAtlas;
}

void SKImageAtlas::beforeDraw(const SkRect &bounds) {
    gCounters[SK_IMAGE_ATLAS_BATCH_DRAWS]++;
    m_batchEpoch++;   // the pending sprites are drawn now
}

bool SKImageAtlas::fits(jint width, jint height) {
    return width > 0 && height > 0 && width <= MAX_CELL && height <= MAX_CELL;
}

bool SKImageAtlas::allocateCell(jint width, jint height, jint *cellX, jint *cellY) {
    const jint shelfHeight = std::max<jint>(SHELF_HEIGHT_STEP,
                                            (height + GAP + SHELF_HEIGHT_STEP - 1) & ~(SHELF_HEIGHT_STEP - 1));
    for (Shelf &shelf : m_shelves) {
        if (shelf.height == shelfHeight && shelf.nextX + width + GAP <= ATLAS_SIZE) {
            *cellX = shelf.nextX;
            *cellY = shelf.top;
            shelf.nextX += width + GAP;
            return true;
        }
    }
    if (m_nextShelfTop + shelfHeight > ATLAS_SIZE) {
        return false;
    }
    m_shelves.push_back(Shelf{m_nextShelfTop, shelfHeight, width + GAP});
    *cellX = 0;
    *cellY = m_nextShelfTop;
    m_nextShelfTop += shelfHeight;
    return true;
}

// Full: the batch is drawn and the recording that sampled the cells ended, then the atlas starts over.
void SKImageAtlas::refill() {
    flush();
    m_recordingOwner->endRecording();
    m_cells.clear();
    m_shelves.clear();
    m_nextShelfTop = 0;
    gCounters[SK_IMAGE_ATLAS_REFILLS]++;
}

bool SKImageAtlas::draw(SkCanvas *canvas, const SkPaint &paint, const SKSurfaceState *sourceState, const sk_sp<SkImage> &source,
                        jint sourceX, jint sourceY, jint width, jint height, jint destX, jint destY) {
    if (sourceState == nullptr || !source || !fits(width, height)) {
        return false;
    }
    const Key key{sourceState->uid(), sourceX, sourceY, width, height};
    auto found = m_cells.find(key);
    bool copy = false;
    if (found == m_cells.end()) {
        jint cellX, cellY;
        if (!allocateCell(width, height, &cellX, &cellY)) {
            refill();
            if (!allocateCell(width, height, &cellX, &cellY)) {
                return false;
            }
        }
        found = m_cells.emplace(key, Cell{cellX, cellY, 0, 0}).first;
        copy = true;
    } else if (found->second.version != sourceState->contentVersion()) {
        // pending sprites of this cell show the content before the source changed: they are drawn first (today a
        // change of a source switches the destination, which draws every batch; kept for batches that survive it)
        if (found->second.batchEpoch == m_batchEpoch) {
            flush();
        }
        copy = true;
    }
    Cell &cell = found->second;
    if (copy) {
        // recorded into the atlas before the mesh that samples it is: Graphite orders the atlas' draws
        // ahead of that mesh (the image is linked to the atlas' device)
        SkPaint copyPaint;
        copyPaint.setBlendMode(SkBlendMode::kSrc);
        m_atlas->getCanvas()->drawImageRect(source,
                SkRect::MakeXYWH((SkScalar) sourceX, (SkScalar) sourceY, (SkScalar) width, (SkScalar) height),
                SkRect::MakeXYWH((SkScalar) cell.x, (SkScalar) cell.y, (SkScalar) width, (SkScalar) height),
                SkSamplingOptions(SkFilterMode::kNearest), &copyPaint, SkCanvas::kStrict_SrcRectConstraint);
        cell.version = sourceState->contentVersion();
        gCounters[SK_IMAGE_ATLAS_COPIES]++;
    }
    m_batch.begin(canvas, paint, m_atlasImage);
    m_batch.addSprite((float) destX, (float) destY, (float) width, (float) height, (float) cell.x, (float) cell.y);
    cell.batchEpoch = m_batchEpoch;
    gCounters[SK_IMAGE_ATLAS_BLITS]++;
    return true;
}

void SKImageAtlas::flush() {
    m_batch.flush();
}

jlong SKImageAtlas::counter(SKImageAtlasCounter counter) {
    return gCounters[counter].load();
}
