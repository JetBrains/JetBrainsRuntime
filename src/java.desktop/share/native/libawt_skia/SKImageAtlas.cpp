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
#include "SKBackend.h"
#include "SKStats.h"
#include "SKQuadBatch.h"

#include "Trace.h"

#include <algorithm>
#include <atomic>
#include <unordered_map>
#include <vector>

#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Surface.h"

static const jint ATLAS_SIZE = 2048;
static const jint MAX_CELL = 128;   // larger sources are drawn on their own
static const jint GAP = 1;          // texels between cells (the sprites sample texel centres only)

namespace {

struct Key {
    uint64_t uid;
    jint sx, sy, w, h;
    bool operator==(const Key &o) const {
        return uid == o.uid && sx == o.sx && sy == o.sy && w == o.w && h == o.h;
    }
};

struct KeyHash {
    size_t operator()(const Key &k) const {
        uint64_t h = k.uid * 0x9E3779B97F4A7C15ull;
        h ^= ((uint64_t) (uint32_t) k.sx << 32 | (uint32_t) k.sy) + 0x7F4A7C15ull + (h << 6) + (h >> 2);
        h ^= ((uint64_t) (uint32_t) k.w << 32 | (uint32_t) k.h) + 0x9E3779B9ull + (h << 6) + (h >> 2);
        return (size_t) h;
    }
};

struct Cell {
    jint x, y;
    uint64_t version;
    uint64_t batchEpoch;   // the batch generation it last joined (SKImageAtlas::batchEpoch)
};

// Rows of cells of one rounded height, filled left to right.
struct Shelf {
    jint y, h, x;
};

std::atomic<jlong> gStats[SK_IMAGE_ATLAS_STAT_COUNT];

} // namespace

struct SKImageAtlas {
    SKConfigContext *cc = nullptr;
    sk_sp<SkSurface> atlas;
    sk_sp<SkImage> atlasImage;
    SKQuadBatch batch;
    std::unordered_map<Key, Cell, KeyHash> cells;
    std::vector<Shelf> shelves;
    jint nextY = 0;
    uint64_t batchEpoch = 1;   // advanced by every draw of the batch: a cell of the current epoch has pending sprites
};

SKImageAtlas *SKImageAtlas_Create(SKConfigContext *cc) {
    if (cc == nullptr || !cc->recorder) {
        return nullptr;
    }
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(cc->recorder.get(),
            SkImageInfo::Make(ATLAS_SIZE, ATLAS_SIZE, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKImageAtlas_Create: cannot create the atlas surface");
        return nullptr;
    }
    auto *ia = new SKImageAtlas();
    ia->cc = cc;
    ia->atlas = std::move(atlas);
    ia->atlas->getCanvas()->clear(SK_ColorTRANSPARENT);
    ia->atlasImage = SkSurfaces::AsImage(ia->atlas);
    if (!ia->atlasImage) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKImageAtlas_Create: cannot image the atlas");
        delete ia;
        return nullptr;
    }
    ia->batch.beforeDraw = [ia](const SkRect &) {
        gStats[SK_IMAGE_ATLAS_BATCH_DRAWS]++;
        ia->batchEpoch++;   // the pending sprites are drawn now
    };
    return ia;
}

void SKImageAtlas_Free(SKImageAtlas *ia) {
    delete ia;
}

bool SKImageAtlas_Fits(jint w, jint h) {
    return w > 0 && h > 0 && w <= MAX_CELL && h <= MAX_CELL;
}

static bool allocCell(SKImageAtlas *ia, jint w, jint h, jint *cx, jint *cy) {
    const jint sh = std::max<jint>(8, (h + GAP + 7) & ~7);   // shelf heights in steps of 8 texels
    for (Shelf &s : ia->shelves) {
        if (s.h == sh && s.x + w + GAP <= ATLAS_SIZE) {
            *cx = s.x;
            *cy = s.y;
            s.x += w + GAP;
            return true;
        }
    }
    if (ia->nextY + sh > ATLAS_SIZE) {
        return false;
    }
    ia->shelves.push_back(Shelf{ia->nextY, sh, w + GAP});
    *cx = 0;
    *cy = ia->nextY;
    ia->nextY += sh;
    return true;
}

// Full: the batch is drawn and the recording that sampled the cells ended, then the atlas starts over.
static void refill(SKImageAtlas *ia) {
    SKImageAtlas_Flush(ia);
    SKBackend_FlushRecording(ia->cc);
    ia->cells.clear();
    ia->shelves.clear();
    ia->nextY = 0;
    gStats[SK_IMAGE_ATLAS_REFILLS]++;
}

bool SKImageAtlas_Draw(SKImageAtlas *ia, SkCanvas *canvas, const SkPaint &paint, SKSurfaceState *st,
                      const sk_sp<SkImage> &source, jint sx, jint sy, jint w, jint h, jint dx, jint dy) {
    if (ia == nullptr || st == nullptr || !source || !SKImageAtlas_Fits(w, h)) {
        return false;
    }
    const Key key{st->uid, sx, sy, w, h};
    auto it = ia->cells.find(key);
    bool copy = false;
    if (it == ia->cells.end()) {
        jint cx, cy;
        if (!allocCell(ia, w, h, &cx, &cy)) {
            refill(ia);
            if (!allocCell(ia, w, h, &cx, &cy)) {
                return false;
            }
        }
        it = ia->cells.emplace(key, Cell{cx, cy, 0, 0}).first;
        copy = true;
    } else if (it->second.version != st->contentVersion) {
        // pending sprites of this cell show the content before the source changed: they are drawn first (today a
        // change of a source switches the destination, which draws every batch; kept for batches that survive it)
        if (it->second.batchEpoch == ia->batchEpoch) {
            SKImageAtlas_Flush(ia);
        }
        copy = true;
    }
    if (copy) {
        // recorded into the atlas before the mesh that samples it is: Graphite orders the atlas' draws
        // ahead of that mesh (the image is linked to the atlas' device)
        SkPaint copyPaint;
        copyPaint.setBlendMode(SkBlendMode::kSrc);
        ia->atlas->getCanvas()->drawImageRect(source, SkRect::MakeXYWH((SkScalar) sx, (SkScalar) sy, (SkScalar) w, (SkScalar) h),
                                              SkRect::MakeXYWH((SkScalar) it->second.x, (SkScalar) it->second.y, (SkScalar) w, (SkScalar) h),
                                              SkSamplingOptions(SkFilterMode::kNearest), &copyPaint,
                                              SkCanvas::kStrict_SrcRectConstraint);
        it->second.version = st->contentVersion;
        gStats[SK_IMAGE_ATLAS_COPIES]++;
        SKStats_Count(SK_COUNT_IMAGE_ATLAS_COPIES);
    }
    ia->batch.begin(canvas, paint, ia->atlasImage);
    ia->batch.addSprite((float) dx, (float) dy, (float) w, (float) h, (float) it->second.x, (float) it->second.y);
    it->second.batchEpoch = ia->batchEpoch;
    gStats[SK_IMAGE_ATLAS_BLITS]++;
    SKStats_Count(SK_COUNT_IMAGE_ATLAS_BLITS);
    return true;
}

void SKImageAtlas_Flush(SKImageAtlas *ia) {
    if (ia != nullptr) {
        ia->batch.flush();
    }
}


jlong SKImageAtlas_Stat(SKImageAtlasStat stat) {
    return gStats[stat].load();
}
