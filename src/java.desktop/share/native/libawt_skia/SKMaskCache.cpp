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
#include "SKBackend.h"
#include "SKQuadBatch.h"

#include "Trace.h"

#include <cstring>
#include <vector>

#include "include/core/SkImage.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Surface.h"

static const jint CACHE_SIZE = 2048;   // 4096 cells: about one wrap per heavy frame
static const jint CELL_SIZE  = 32;
static const jint CELLS_PER_ROW = CACHE_SIZE / CELL_SIZE;
static const jint CELL_COUNT = CELLS_PER_ROW * CELLS_PER_ROW;

struct SKMaskCache {
    SKConfigContext *cc = nullptr;
    sk_sp<SkSurface> atlas;
    sk_sp<SkImage> atlasImage;
    jint nextCell = 0;
    SKQuadBatch batch;
    // Tiles are copied into one atlas row on the CPU and uploaded per row: a
    // writePixels per 32x32 tile costs more than the tile itself.
    std::vector<unsigned char> staging;
    jint stagingRow = -1;
    bool stagingDirty = false;
};

// Records the staged row's upload. Must precede the draws that sample it: the
// atlas' pending uploads are only ordered ahead of a destination's draws that
// are recorded after them.
static void uploadStaging(SKMaskCache *mc) {
    if (mc->stagingDirty) {
        SkPixmap pm(SkImageInfo::MakeA8(CACHE_SIZE, CELL_SIZE), mc->staging.data(), (size_t) CACHE_SIZE);
        mc->atlas->writePixels(pm, 0, mc->stagingRow);
        mc->stagingDirty = false;
    }
}

SKMaskCache *SKMaskCache_Create(SKConfigContext *cc) {
    if (cc == nullptr || !cc->recorder) {
        return nullptr;
    }
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(cc->recorder.get(), SkImageInfo::MakeA8(CACHE_SIZE, CACHE_SIZE));
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKMaskCache_Create: cannot create the A8 atlas surface");
        return nullptr;
    }
    auto *mc = new SKMaskCache();
    mc->cc = cc;
    mc->atlas = std::move(atlas);
    mc->atlasImage = SkSurfaces::AsImage(mc->atlas);
    if (!mc->atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKMaskCache_Create: cannot image the A8 atlas");
        delete mc;
        return nullptr;
    }
    mc->staging.resize((size_t) CACHE_SIZE * CELL_SIZE);
    mc->batch.beforeDraw = [mc] { uploadStaging(mc); };
    return mc;
}

void SKMaskCache_Free(SKMaskCache *mc) {
    delete mc;
}

bool SKMaskCache_Fits(jint w, jint h) {
    return w <= CELL_SIZE && h <= CELL_SIZE;
}

// Next free cell; wrapping around overwrites cells that this recording's draws
// may still sample, so the batch is drawn and the recording ended first.
static void allocCell(SKMaskCache *mc, jint *cx, jint *cy) {
    if (mc->nextCell == CELL_COUNT) {
        SKMaskCache_Flush(mc);
        SKBackend_FlushRecording(mc->cc);
        mc->nextCell = 0;
    }
    *cx = (mc->nextCell % CELLS_PER_ROW) * CELL_SIZE;
    *cy = (mc->nextCell / CELLS_PER_ROW) * CELL_SIZE;
    mc->nextCell++;
}

void SKMaskCache_Draw(SKMaskCache *mc, SkCanvas *canvas, const SkPaint &paint,
                      jint x, jint y, jint w, jint h, const unsigned char *mask, jint maskscan) {
    jint cx, cy;
    allocCell(mc, &cx, &cy);
    if (cy != mc->stagingRow) {
        uploadStaging(mc);
        mc->stagingRow = cy;
    }
    for (jint row = 0; row < h; row++) {
        memcpy(mc->staging.data() + (size_t) row * CACHE_SIZE + cx, mask + (size_t) row * maskscan, (size_t) w);
    }
    if (paint.getShader() == nullptr) {
        // alpha-only atlas in the mesh: the tiles mask the paint colour. The
        // batch uploads the staged row before it draws (beforeDraw), also when
        // begin() flushes an older batch for a different paint.
        mc->stagingDirty = true;
        mc->batch.begin(canvas, paint, mc->atlasImage);
        mc->batch.addSprite(x, y, w, h, cx, cy);
        return;
    }
    // A gradient or texture paint has to be sampled in canvas coordinates, which
    // a mesh with atlas texture coordinates cannot express without sk_FragCoord
    // (private SkSL in m156). Draw such tiles one by one: an alpha-only image
    // masks the paint's shader. The tile is uploaded on its own so the draw can
    // sample it right away; its copy in the staging row keeps a later row upload
    // from overwriting the cell with stale data.
    mc->batch.flush();
    SkPixmap tile(SkImageInfo::MakeA8(w, h), mask, (size_t) maskscan);
    mc->atlas->writePixels(tile, cx, cy);
    canvas->drawImageRect(mc->atlasImage.get(), SkRect::MakeXYWH(cx, cy, w, h), SkRect::MakeXYWH(x, y, w, h),
                          SkSamplingOptions(SkFilterMode::kNearest), &paint, SkCanvas::kStrict_SrcRectConstraint);
}

void SKMaskCache_Flush(SKMaskCache *mc) {
    if (mc != nullptr) {
        uploadStaging(mc);
        mc->batch.flush();
    }
}
