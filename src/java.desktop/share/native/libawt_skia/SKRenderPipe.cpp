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
// Fills and draws of rects, lines, spans, polygons and parallelograms (SKRenderPipe.h).
// All functions run on the Skia queue flusher thread.

#include "SKRenderPipe.h"
#include "SKContext.h"
#include "SKDirectEntry.h"
#include "SKPaints.h"
#include "SKPendingBatches.h"
#include "SKRectBatch.h"
#include "SKRenderQueueLayout.h"

#include "sun_java2d_skia_SKRenderPipe.h"

#include "include/core/SkPathBuilder.h"

#include <algorithm>
// ReSharper disable once CppUnusedIncludeDirective - std::abs is used here
#include <cstdlib>

namespace {

// A diagonal 1-pixel line as one quad: per pixel column (row, for a y-major line) a band one pixel high
// centered on the line through the end points' pixel centers, so the rasterizer's pixel-center rule picks
// the pixel nearest to the line in every column, as Bresenham's stepping does. Where the line passes
// half-way between two pixel centers the rasterizer decides; the software loops' error term, the GPU
// pipelines' line primitives and this band each resolve such ties their own way (a liberty). One quad
// instead of one per run: a long diagonal costs what a short one does.
void lineAsBand(const jint x1, const jint y1, const jint x2, const jint y2, const bool shorten, const bool xMajor) {
    // the major-axis extent in pixel edges; the end point dropped if shortened
    const jint majorStart = xMajor ? x1 : y1, majorEnd = xMajor ? x2 : y2, step = majorEnd > majorStart ? 1 : -1;
    const jint majorLast = shorten ? majorEnd - step : majorEnd;
    if ((majorLast - majorStart) * step < 0) {
        return;
    }
    const auto low = static_cast<float>(std::min(majorStart, majorLast));
    const auto high = static_cast<float>(std::max(majorStart, majorLast)) + 1.0f;
    const jint deltaMajor = xMajor ? x2 - x1 : y2 - y1, deltaMinor = xMajor ? y2 - y1 : x2 - x1;
    const float slope = static_cast<float>(deltaMinor) / static_cast<float>(deltaMajor);
    const float minorCenter = static_cast<float>(xMajor ? y1 : x1) + 0.5f;
    const float majorCenter = static_cast<float>(majorStart) + 0.5f;
    const float minorAtLow = minorCenter + (low - majorCenter) * slope;
    const float minorAtHigh = minorCenter + (high - majorCenter) * slope;
    SkPoint corners[4];
    if (xMajor) {
        corners[0] = {.fX = low, .fY = minorAtLow - 0.5f};
        corners[1] = {.fX = high, .fY = minorAtHigh - 0.5f};
        corners[2] = {.fX = high, .fY = minorAtHigh + 0.5f};
        corners[3] = {.fX = low, .fY = minorAtLow + 0.5f};
    } else {
        corners[0] = {.fX = minorAtLow - 0.5f, .fY = low};
        corners[1] = {.fX = minorAtLow + 0.5f, .fY = low};
        corners[2] = {.fX = minorAtHigh + 0.5f, .fY = high};
        corners[3] = {.fX = minorAtHigh - 0.5f, .fY = high};
    }
    SKRectBatch::batch().addQuad(corners);
}

// Java2D's 1-pixel lines: one pixel per step along the major axis, both endpoints inclusive unless
// `shorten` drops the pixel at (x2, y2), as DrawPolygons.c does for the inner joints of a polyline (a
// joint drawn twice would show under XOR or a translucent color). Horizontal and vertical lines are
// one rect; diagonal ones one quad (lineAsBand).
void pixelLine(const jint x1, const jint y1, const jint x2, const jint y2, const bool shorten = false) {
    const jint dx = x2 - x1, dy = y2 - y1;
    if (dy == 0 || dx == 0) {
        // SETORDERED: the shortened end is always the second point's
        const bool horizontal = dy == 0;
        const jint from = horizontal ? x1 : y1, to = horizontal ? x2 : y2;
        jint low, high;
        if (from < to) {
            low = from;
            high = to - (shorten ? 1 : 0);
        } else {
            low = to + (shorten ? 1 : 0);
            high = from;
        }
        if (low > high) return;
        if (horizontal) {
            SKRectBatch::batch().addRect(low, y1, high + 1, y1 + 1);
        } else {
            SKRectBatch::batch().addRect(x1, low, x1 + 1, high + 1);
        }
        return;
    }
    lineAsBand(x1, y1, x2, y2, shorten, std::abs(dx) >= std::abs(dy));
}

void addParallelogram(SkPathBuilder &pathBuilder, const float x, const float y, const float dx21, const float dy21,
                      const float dx12, const float dy12) {
    pathBuilder.moveTo(x, y);
    pathBuilder.lineTo(x + dx21, y + dy21);
    pathBuilder.lineTo(x + dx21 + dx12, y + dy21 + dy12);
    pathBuilder.lineTo(x + dx12, y + dy12);
    pathBuilder.close();
}

void parallelogramPoints(SkPoint corners[4], const float x, const float y, const float dx21, const float dy21,
                         const float dx12, const float dy12) {
    corners[0] = {.fX = x, .fY = y};
    corners[1] = {.fX = x + dx21, .fY = y + dy21};
    corners[2] = {.fX = x + dx21 + dx12, .fY = y + dy21 + dy12};
    corners[3] = {.fX = x + dx12, .fY = y + dy12};
}

} // namespace

void SKRenderPipe::fillRect(const jint x, const jint y, const jint width, const jint height) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    rectBatch.addRect(x, y, x + width, y + height);
}

void SKRenderPipe::drawRect(const jint x, const jint y, const jint width, const jint height) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    if (width < 0 || height < 0) return;
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    if (width < 2 || height < 2) {
        // Java draws a (width+1)x(height+1) outline; degenerate outlines are solid.
        rectBatch.addRect(x, y, x + width + 1, y + height + 1);
        return;
    }
    rectBatch.addRect(x, y, x + width + 1, y + 1);                           // top
    rectBatch.addRect(x, y + height, x + width + 1, y + height + 1);         // bottom
    rectBatch.addRect(x, y + 1, x + 1, y + height);                          // left
    rectBatch.addRect(x + width, y + 1, x + width + 1, y + height);          // right
}

void SKRenderPipe::drawLine(const jint x1, const jint y1, const jint x2, const jint y2) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKRectBatch::batch().begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    pixelLine(x1, y1, x2, y2);
}

void SKRenderPipe::drawPixel(const jint x, const jint y) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    rectBatch.addRect(x, y, x + 1, y + 1);
}

void SKRenderPipe::drawScanlines(const jint count, const jint *scanlines) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    for (jint index = 0; index < count; index++) {
        const jint *scanline = scanlines + SK_INTS_PER_SCANLINE * index;
        const jint x1 = scanline[0], x2 = scanline[1], y = scanline[2];
        rectBatch.addRect(x1, y, x2 + 1, y + 1);
    }
}

void SKRenderPipe::fillSpans(const jint count, const jint *spans) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    for (jint index = 0; index < count; index++) {
        const jint *span = spans + SK_INTS_PER_SPAN * index;
        rectBatch.addRect(span[0], span[1], span[2], span[3]);
    }
}

void SKRenderPipe::drawPoly(const jint pointCount, const jboolean isClosed, const jint transX, const jint transY,
                            const jint *xPoints, const jint *yPoints) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    if (pointCount < 2) {
        return;   // a single point draws nothing (DrawPolygons.c)
    }
    SKRectBatch::batch().begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    // As DrawPolygons.c: every segment but the last of an open polyline drops
    // its end pixel (the next segment draws it); a closed polygon shortens all
    // of them and closes back to the first point, a degenerate one is a pixel.
    const jint firstX = xPoints[0] + transX, firstY = yPoints[0] + transY;
    jint previousX = firstX, previousY = firstY;
    bool degenerate = true;   // every point so far is the first
    for (jint index = 1; index < pointCount; index++) {
        const jint x = xPoints[index] + transX, y = yPoints[index] + transY;
        degenerate = degenerate && previousX == x && previousY == y;
        pixelLine(previousX, previousY, x, y, index < pointCount - 1 || isClosed);
        previousX = x;
        previousY = y;
    }
    if (isClosed && (degenerate || previousX != firstX || previousY != firstY)) {
        pixelLine(previousX, previousY, firstX, firstY, !degenerate);
    }
}

void SKRenderPipe::fillParallelogram(const bool antialiased, const jfloat x11, const jfloat y11, const jfloat dx21,
                                     const jfloat dy21, const jfloat dx12, const jfloat dy12) {
    if (!antialiased) {
        // non-AA: a quad in the mesh batch, like the other non-AA geometry
        if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
            return;
        }
        SkPoint corners[4];
        parallelogramPoints(corners, x11, y11, dx21, dy21, dx12, dy12);
        SKQuadBatch &rectBatch = SKRectBatch::batch();
        rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
        rectBatch.addQuad(corners);
        return;
    }
    if (!SKContext::current().beginDraw()) {
        return;
    }
    SkPathBuilder pathBuilder;
    addParallelogram(pathBuilder, x11, y11, dx21, dy21, dx12, dy12);
    SKContext::current().canvas()->drawPath(pathBuilder.detach(), SKPaints::makePaint(antialiased));
}

void SKRenderPipe::drawParallelogram(const bool antialiased, const jfloat x11, const jfloat y11, const jfloat dx21,
                                     const jfloat dy21, const jfloat dx12, const jfloat dy12, const jfloat lwr21,
                                     const jfloat lwr12) {
    // The given parallelogram is the stroke centerline (see MTLRenderer_DrawParallelogram):
    // grow it by half the line width for the outer edge, shrink it for the inner one.
    const float ldx21 = dx21 * lwr21, ldy21 = dy21 * lwr21;
    const float ldx12 = dx12 * lwr12, ldy12 = dy12 * lwr12;
    const float ox11 = x11 - (ldx21 + ldx12) / 2.0f, oy11 = y11 - (ldy21 + ldy12) / 2.0f;
    if (!(antialiased ? SKContext::current().beginDraw() : SKContext::current().beginDraw(SKBatchKind::Rects))) {
        return;
    }
    const bool hole = lwr21 < 1.0f && lwr12 < 1.0f;   // otherwise the stroke covers the interior
    const float ix11 = x11 + (ldx21 + ldx12) / 2.0f, iy11 = y11 + (ldy21 + ldy12) / 2.0f;
    if (!antialiased) {
        SkPoint outer[4], inner[4];
        parallelogramPoints(outer, ox11, oy11, dx21 + ldx21, dy21 + ldy21, dx12 + ldx12, dy12 + ldy12);
        SKQuadBatch &rectBatch = SKRectBatch::batch();
        rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
        if (hole) {
            parallelogramPoints(inner, ix11, iy11, dx21 - ldx21, dy21 - ldy21, dx12 - ldx12, dy12 - ldy12);
            rectBatch.addRing(outer, inner);
        } else {
            rectBatch.addQuad(outer);
        }
        return;
    }
    SkPathBuilder pathBuilder;
    pathBuilder.setFillType(SkPathFillType::kEvenOdd);
    addParallelogram(pathBuilder, ox11, oy11, dx21 + ldx21, dy21 + ldy21, dx12 + ldx12, dy12 + ldy12);
    if (hole) {
        addParallelogram(pathBuilder, ix11, iy11, dx21 - ldx21, dy21 - ldy21, dx12 - ldx12, dy12 - ldy12);
    }
    SKContext::current().canvas()->drawPath(pathBuilder.detach(), SKPaints::makePaint(antialiased));
}

// The direct entry (SKRenderQueue.flushAndInvokeNow): an operation larger than the render buffer.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKRenderPipe_drawPolyNative(JNIEnv *env, jobject,
                                                 // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
                                                 const jintArray xPointArray, const jintArray yPointArray,
                                                 const jint pointCount, const jboolean isClosed,
                                                 const jint transX, const jint transY)
{
    SKDirectEntry entry(env);
    if (auto *xPoints = static_cast<jint *>(env->GetPrimitiveArrayCritical(xPointArray, nullptr));
        xPoints != nullptr) {
        if (auto *yPoints = static_cast<jint *>(env->GetPrimitiveArrayCritical(yPointArray, nullptr));
            yPoints != nullptr) {
            SKRenderPipe::drawPoly(pointCount, isClosed, transX, transY, xPoints, yPoints);
            env->ReleasePrimitiveArrayCritical(yPointArray, yPoints, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(xPointArray, xPoints, JNI_ABORT);
    }
    return entry.end();
}

} // extern "C"
