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

#ifndef SKQuadBatch_h_Included
#define SKQuadBatch_h_Included

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include "jni.h"
#include <functional>
#include <vector>

// Quads of one paint drawn as a single mesh. Every Graphite draw costs about
// 2 us of CPU and has its own paint key, so spans, scanlines, Bresenham runs,
// rect fills and outlines, non-AA parallelograms, and the glyph and mask
// sprites are collected here instead. A mesh is rasterized with the
// pixel-centre rule and no coverage, which is what Java2D's non-AA primitives
// specify (Graphite would anti-alias anything not pixel-aligned). Without an
// atlas the paint's shader is sampled in canvas coordinates; with an atlas the
// quads carry texture coordinates and the atlas is the paint's shader: an
// alpha-only atlas takes the paint colour, an RGB atlas feeds the blender.
class SKQuadBatch {
public:
    SKQuadBatch() {
        positions.reserve(4 * 1024);
        texCoords.reserve(4 * 1024);
        indices.reserve(6 * 1024);
    }

    // Starts (or continues) a batch for canvas/paint/atlas; a different triple
    // draws the open batch first.
    void begin(SkCanvas *c, const SkPaint &p, const sk_sp<SkImage> &atlas = nullptr) {
        if (canvas == c && paint == p && atlasImage.get() == atlas.get()) {
            return;
        }
        flush();
        canvas = c;
        paint = p;
        atlasImage = atlas;
        meshPaint = p;
        if (atlas) {
            meshPaint.setShader(atlas->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                  SkSamplingOptions(SkFilterMode::kNearest)));
        }
    }

    bool active() const { return canvas != nullptr; }

    // Axis-aligned sprite from the atlas at (tx, ty).
    void addSprite(float x, float y, float w, float h, float tx, float ty) {
        const SkPoint pos[4] = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        const SkPoint tex[4] = {{tx, ty}, {tx + w, ty}, {tx + w, ty + h}, {tx, ty + h}};
        push(pos, tex);
    }

    // Integer rect [l, r) x [t, b), as spans and runs are given.
    void addRect(jint l, jint t, jint r, jint b) {
        if (r <= l || b <= t) {
            return;
        }
        const SkPoint pos[4] = {{(float) l, (float) t}, {(float) r, (float) t}, {(float) r, (float) b}, {(float) l, (float) b}};
        push(pos, nullptr);
    }

    void addQuad(const SkPoint pts[4]) {
        push(pts, nullptr);
    }

    // Outer quad minus inner quad (same corner order): the four side quads.
    void addRing(const SkPoint outer[4], const SkPoint inner[4]) {
        for (int i = 0; i < 4; i++) {
            const int j = (i + 1) % 4;
            const SkPoint side[4] = {outer[i], outer[j], inner[j], inner[i]};
            push(side, nullptr);
        }
    }

    // Runs right before a mesh is drawn, whoever triggers the draw. The mask
    // cache uploads its staged atlas cells here: an upload is only ordered ahead
    // of the destination's pass if a draw sampling the atlas is recorded after it.
    std::function<void()> beforeDraw;

    // Draws what is queued and closes the batch.
    void flush() {
        drawPending();
        canvas = nullptr;
        atlasImage.reset();
    }

private:
    void push(const SkPoint pos[4], const SkPoint *tex) {
        if (positions.size() + 4 > 0xffff) {
            drawPending();   // 16-bit indices
        }
        uint16_t base = (uint16_t) positions.size();
        positions.insert(positions.end(), pos, pos + 4);
        if (tex != nullptr) {
            texCoords.insert(texCoords.end(), tex, tex + 4);
        }
        const uint16_t quad[6] = { base, (uint16_t) (base + 1), (uint16_t) (base + 2),
                                   base, (uint16_t) (base + 2), (uint16_t) (base + 3) };
        indices.insert(indices.end(), quad, quad + 6);
    }

    void drawPending() {
        if (canvas != nullptr && !indices.empty()) {
            if (beforeDraw) {
                beforeDraw();
            }
            sk_sp<SkVertices> mesh = SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode,
                                                          (int) positions.size(), positions.data(),
                                                          texCoords.empty() ? nullptr : texCoords.data(), nullptr,
                                                          (int) indices.size(), indices.data());
            canvas->drawVertices(mesh, SkBlendMode::kModulate, meshPaint);
        }
        positions.clear();
        texCoords.clear();
        indices.clear();
    }

    SkCanvas *canvas = nullptr;
    SkPaint paint;
    SkPaint meshPaint;
    sk_sp<SkImage> atlasImage;
    std::vector<SkPoint> positions;
    std::vector<SkPoint> texCoords;
    std::vector<uint16_t> indices;
};

#endif /* SKQuadBatch_h_Included */
