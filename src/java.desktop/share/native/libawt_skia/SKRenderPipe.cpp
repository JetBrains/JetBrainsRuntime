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
#include "include/core/SkRect.h"

#include <algorithm>
#include <cstdlib>

namespace {

// The band of lineAsBand is shifted toward the end point by this fraction of the smallest
// distance a pixel center can have from the line (1 / majorExtent), so that a tie goes the way
// Bresenham's error term sends it.
constexpr float TIE_BREAK_FRACTION = 1.0f / 4.0f;

// A diagonal 1-pixel line as one quad: per pixel column (row, for a y-major
// line) a band one pixel high centered on the ideal line through the end
// points' pixel centers, so the rasterizer's pixel-center rule picks the pixel
// nearest to the line in every column, as the Bresenham stepping does. The
// band is shifted toward the end point by a quarter of the smallest distance
// a pixel center can have from it, so a tie (the line half-way between two
// pixel centers) goes the way the error term sends it. Exact while that
// distance stays above the rasterizer's vertex precision (1/256 px on D3D11):
// to a major extent of about 128 px; past it an occasional pixel moves by one
// across the line (LineConformance: 3.5 % of lines at 256 px, a few pixels
// each), still far closer to the loops than the line primitives of the other
// pipelines. One quad instead of one per run: a long diagonal costs what a
// short one does.
void lineAsBand(jint x1, jint y1, jint x2, jint y2, bool shorten, bool xMajor, jint majorExtent) {
    // the major-axis extent in pixel edges; the end point dropped if shortened
    const jint majorStart = xMajor ? x1 : y1, majorEnd = xMajor ? x2 : y2, step = (majorEnd > majorStart) ? 1 : -1;
    const jint majorLast = shorten ? majorEnd - step : majorEnd;
    if ((majorLast - majorStart) * step < 0) {
        return;
    }
    const float low = (float) std::min(majorStart, majorLast), high = (float) std::max(majorStart, majorLast) + 1.0f;
    const jint deltaMajor = xMajor ? x2 - x1 : y2 - y1, deltaMinor = xMajor ? y2 - y1 : x2 - x1;
    const float slope = (float) deltaMinor / (float) deltaMajor;
    const float bias = (deltaMinor > 0 ? 1.0f : -1.0f) * (TIE_BREAK_FRACTION / (float) majorExtent);
    const float minorCenter = (float) (xMajor ? y1 : x1) + 0.5f, majorCenter = (float) majorStart + 0.5f;
    const float minorAtLow = minorCenter + (low - majorCenter) * slope + bias;
    const float minorAtHigh = minorCenter + (high - majorCenter) * slope + bias;
    SkPoint corners[4];
    if (xMajor) {
        corners[0] = {low, minorAtLow - 0.5f};
        corners[1] = {high, minorAtHigh - 0.5f};
        corners[2] = {high, minorAtHigh + 0.5f};
        corners[3] = {low, minorAtLow + 0.5f};
    } else {
        corners[0] = {minorAtLow - 0.5f, low};
        corners[1] = {minorAtLow + 0.5f, low};
        corners[2] = {minorAtHigh + 0.5f, high};
        corners[3] = {minorAtHigh - 0.5f, high};
    }
    SKRectBatch::batch().addQuad(corners);
}

// Java2D's 1-pixel lines: the Bresenham stepping of the software loops
// (SETUP_BRESENHAM in DrawLine.c and the DRAWLINE loop), both endpoints
// inclusive unless `shorten` drops the pixel at (x2, y2) as DrawPolygons.c does
// for the inner joints of a polyline. Horizontal and vertical lines are one
// rect; diagonal ones one quad (lineAsBand).
void bresenhamLine(jint x1, jint y1, jint x2, jint y2, bool shorten = false) {
    const jint dx = x2 - x1, dy = y2 - y1;
    const jint absDx = std::abs(dx), absDy = std::abs(dy);
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
    const bool xMajor = absDx >= absDy;
    lineAsBand(x1, y1, x2, y2, shorten, xMajor, xMajor ? absDx : absDy);
}

void addParallelogram(SkPathBuilder &pathBuilder, float x, float y, float dx21, float dy21, float dx12, float dy12) {
    pathBuilder.moveTo(x, y);
    pathBuilder.lineTo(x + dx21, y + dy21);
    pathBuilder.lineTo(x + dx21 + dx12, y + dy21 + dy12);
    pathBuilder.lineTo(x + dx12, y + dy12);
    pathBuilder.close();
}

void parallelogramPoints(SkPoint corners[4], float x, float y, float dx21, float dy21, float dx12, float dy12) {
    corners[0] = {x, y};
    corners[1] = {x + dx21, y + dy21};
    corners[2] = {x + dx21 + dx12, y + dy21 + dy12};
    corners[3] = {x + dx12, y + dy12};
}

} // namespace

void SKRenderPipe::fillRect(jint x, jint y, jint width, jint height) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    rectBatch.addRect(x, y, x + width, y + height);
}

void SKRenderPipe::drawRect(jint x, jint y, jint width, jint height) {
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

void SKRenderPipe::drawLine(jint x1, jint y1, jint x2, jint y2) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKRectBatch::batch().begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    bresenhamLine(x1, y1, x2, y2);
}

void SKRenderPipe::drawPixel(jint x, jint y) {
    if (!SKContext::current().beginDraw(SKBatchKind::Rects)) {
        return;
    }
    SKQuadBatch &rectBatch = SKRectBatch::batch();
    rectBatch.begin(SKContext::current().canvas(), SKPaints::makePaint(false));
    rectBatch.addRect(x, y, x + 1, y + 1);
}

void SKRenderPipe::drawScanlines(jint count, const jint *scanlines) {
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

void SKRenderPipe::fillSpans(jint count, const jint *spans) {
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

void SKRenderPipe::drawPoly(jint pointCount, jboolean isClosed, jint transX, jint transY,
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
        bresenhamLine(previousX, previousY, x, y, index < pointCount - 1 || isClosed);
        previousX = x;
        previousY = y;
    }
    if (isClosed && (degenerate || previousX != firstX || previousY != firstY)) {
        bresenhamLine(previousX, previousY, firstX, firstY, !degenerate);
    }
}

void SKRenderPipe::fillParallelogram(bool antialiased, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12) {
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

void SKRenderPipe::drawParallelogram(bool antialiased, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12,
                                     jfloat lwr21, jfloat lwr12) {
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
Java_sun_java2d_skia_SKRenderPipe_drawPolyNative(JNIEnv *env, jobject renderPipe,
                                                 jintArray xPointArray, jintArray yPointArray,
                                                 jint pointCount, jboolean isClosed, jint transX, jint transY)
{
    SKDirectEntry entry(env);
    jint *xPoints = (jint *) env->GetPrimitiveArrayCritical(xPointArray, nullptr);
    if (xPoints != nullptr) {
        jint *yPoints = (jint *) env->GetPrimitiveArrayCritical(yPointArray, nullptr);
        if (yPoints != nullptr) {
            SKRenderPipe::drawPoly(pointCount, isClosed, transX, transY, xPoints, yPoints);
            env->ReleasePrimitiveArrayCritical(yPointArray, yPoints, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(xPointArray, xPoints, JNI_ABORT);
    }
    return entry.end();
}

} // extern "C"
