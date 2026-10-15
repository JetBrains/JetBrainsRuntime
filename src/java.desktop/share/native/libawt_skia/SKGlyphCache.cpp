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

#include "include/core/SkImage.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Surface.h"

#include <vector>

// Same geometry as MTLTextRenderer's caches, twice the side: an IDE frame holds
// far more distinct glyphs than 256 cells.
static const jint CACHE_SIZE = 1024;
static const jint CELL_SIZE  = 32;

struct SKGlyphCache {
    SKConfigContext *cc = nullptr;
    bool lcd = false;
    GlyphCacheInfo *info = nullptr;
    sk_sp<SkSurface> atlas;
    sk_sp<SkImage> atlasImage;

    SKQuadBatch batch;
    std::vector<unsigned char> lcdRow;   // RGB -> RGBA conversion buffer
};

// AccelGlyphCache's flush callback takes no context: the cache that is adding
// a glyph (and may therefore evict) is the one being flushed.
static SKGlyphCache *gAdding = nullptr;

// Called by AccelGlyphCache_AddGlyph right before it overwrites an occupied
// cell. Draw what refers to the old content, then end the recording: Graphite
// flushes the atlas' uploads ahead of the destination's pending draws, so an
// overwrite inside the same recording would be visible to the sprites queued
// before it.
static void flushForEviction() {
    if (gAdding == nullptr) {
        return;
    }
    gAdding->batch.flush();
    SKBackend_FlushRecording(gAdding->cc);
}

SKGlyphCache *SKGlyphCache_Create(SKConfigContext *cc, bool lcd) {
    if (cc == nullptr || !cc->recorder) {
        return nullptr;
    }
    SkImageInfo info = lcd ? SkImageInfo::Make(CACHE_SIZE, CACHE_SIZE, kRGBA_8888_SkColorType, kOpaque_SkAlphaType)
                           : SkImageInfo::MakeA8(CACHE_SIZE, CACHE_SIZE);
    sk_sp<SkSurface> atlas = SkSurfaces::RenderTarget(cc->recorder.get(), info);
    if (!atlas) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGlyphCache_Create: cannot create a %s atlas surface", lcd ? "RGBA" : "A8");
        return nullptr;
    }
    auto *gc = new SKGlyphCache();
    gc->cc = cc;
    gc->lcd = lcd;
    gc->info = AccelGlyphCache_Init(CACHE_SIZE, CACHE_SIZE, CELL_SIZE, CELL_SIZE, flushForEviction);
    if (gc->info == nullptr) {
        delete gc;
        return nullptr;
    }
    gc->atlas = std::move(atlas);
    gc->atlasImage = SkSurfaces::AsImage(gc->atlas);
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
    return gc != nullptr && glyph->width <= CELL_SIZE && glyph->height <= CELL_SIZE;
}

void SKGlyphCache_Begin(SKGlyphCache *gc, SkCanvas *canvas, const SkPaint &paint) {
    gc->batch.begin(canvas, paint, gc->atlasImage);
}

static void upload(SKGlyphCache *gc, const GlyphInfo *glyph, const CacheCellInfo *cell, jint subimage, jboolean rgbOrder) {
    const int w = glyph->width, h = glyph->height;
    if (!gc->lcd) {
        const unsigned char *data = glyph->image + (size_t) glyph->rowBytes * h * subimage;
        SkPixmap pm(SkImageInfo::MakeA8(w, h), data, glyph->rowBytes);
        gc->atlas->writePixels(pm, cell->x, cell->y);
        return;
    }
    // LCD glyph: 3 bytes of coverage per pixel, in the order the strike asked for.
    gc->lcdRow.resize((size_t) w * h * 4);
    for (int row = 0; row < h; row++) {
        const unsigned char *src = glyph->image + (size_t) row * glyph->rowBytes;
        unsigned char *dst = gc->lcdRow.data() + (size_t) row * w * 4;
        for (int col = 0; col < w; col++, src += 3, dst += 4) {
            dst[0] = rgbOrder ? src[0] : src[2];
            dst[1] = src[1];
            dst[2] = rgbOrder ? src[2] : src[0];
            dst[3] = 0xff;
        }
    }
    SkPixmap pm(SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kOpaque_SkAlphaType), gc->lcdRow.data(), (size_t) w * 4);
    gc->atlas->writePixels(pm, cell->x, cell->y);
}

bool SKGlyphCache_Draw(SKGlyphCache *gc, GlyphInfo *glyph, jint subimage, jboolean rgbOrder, float x, float y) {
    // Cached already? The glyph's cell chain interleaves the cells of all
    // caches; sub-pixel strikes keep one cell per sub-image.
    CacheCellInfo *cell = (CacheCellInfo *) glyph->cellInfo;
    while (cell != nullptr && !(cell->cacheInfo == gc->info && cell->glyphSubimage == subimage)) {
        cell = cell->nextGCI;
    }
    if (cell == nullptr) {
        gAdding = gc;
        cell = AccelGlyphCache_AddGlyph(gc->info, glyph);
        gAdding = nullptr;
        if (cell == nullptr) {
            return false;
        }
        cell->glyphSubimage = subimage;
        upload(gc, glyph, cell, subimage, rgbOrder);
    }
    cell->timesRendered++;
    gc->batch.addSprite(x, y, glyph->width, glyph->height, cell->x, cell->y);
    return true;
}

void SKGlyphCache_Flush(SKGlyphCache *gc) {
    if (gc != nullptr) {
        gc->batch.flush();
    }
}
