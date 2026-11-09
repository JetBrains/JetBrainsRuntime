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
// Fills and draws of shapes Skia rasterizes analytically (SKAnalyticShapes.h).
// All functions run on the Skia queue flusher thread.

#include "SKAnalyticShapes.h"
#include "SKContext.h"
#include "SKPaints.h"

#include "java_awt_geom_PathIterator.h"
#include "sun_java2d_skia_SKAnalyticShapes.h"

#include "include/core/SkMatrix.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkRRect.h"
#include "include/core/SkRect.h"

#include <algorithm>
#include <array>

namespace {

// The layout of SKAnalyticShapes' operands (SKAnalyticShapes.java): the stroke's floats
// (a BasicStroke's width, cap, join and miter limit) and the geometry's floats of a rect,
// round rect or oval (x, y, width, height, and the round rect's arc width and height).
enum StrokeParam { STROKE_WIDTH = 0, STROKE_CAP, STROKE_JOIN, STROKE_MITER_LIMIT };
enum ShapeParam { SHAPE_X = 0, SHAPE_Y, SHAPE_WIDTH, SHAPE_HEIGHT, SHAPE_ARC_WIDTH, SHAPE_ARC_HEIGHT };
// BasicStroke.CAP_BUTT, CAP_ROUND, CAP_SQUARE and JOIN_MITER, JOIN_ROUND, JOIN_BEVEL, in their order.
constexpr std::array<SkPaint::Cap, 3> STROKE_CAPS = { SkPaint::kButt_Cap, SkPaint::kRound_Cap, SkPaint::kSquare_Cap };
constexpr std::array<SkPaint::Join, 3> STROKE_JOINS = { SkPaint::kMiter_Join, SkPaint::kRound_Join, SkPaint::kBevel_Join };

// The path's segment types as PathIterator numbers them, applied to the coordinates in sequence.
SkPath buildPath(jint windingRule, jint numTypes, const jint *types, const jfloat *coords) {
    SkPathBuilder pathBuilder(windingRule == java_awt_geom_PathIterator_WIND_EVEN_ODD ? SkPathFillType::kEvenOdd
                                                                                      : SkPathFillType::kWinding);
    const jfloat *coordinate = coords;
    for (jint index = 0; index < numTypes; index++) {
        switch (types[index]) {
            case java_awt_geom_PathIterator_SEG_MOVETO:
                pathBuilder.moveTo(coordinate[0], coordinate[1]);
                coordinate += 2;
                break;
            case java_awt_geom_PathIterator_SEG_LINETO:
                pathBuilder.lineTo(coordinate[0], coordinate[1]);
                coordinate += 2;
                break;
            case java_awt_geom_PathIterator_SEG_QUADTO:
                pathBuilder.quadTo(coordinate[0], coordinate[1], coordinate[2], coordinate[3]);
                coordinate += 4;
                break;
            case java_awt_geom_PathIterator_SEG_CUBICTO:
                pathBuilder.cubicTo(coordinate[0], coordinate[1], coordinate[2], coordinate[3], coordinate[4], coordinate[5]);
                coordinate += 6;
                break;
            default:   // SEG_CLOSE
                pathBuilder.close();
                break;
        }
    }
    return pathBuilder.detach();
}

} // namespace

void SKAnalyticShapes::drawShape(bool fill, jint kind, const jfloat *xform, const jfloat *stroke,
                                 const jfloat *params, jint windingRule, jint numTypes, const jint *types,
                                 jint numCoords, const jfloat *coords) {
    if (!SKContext::current().beginDraw()) {
        return;
    }
    SkCanvas *canvas = SKContext::current().canvas();
    SkPaint paint = SKPaints::makePaint(true);
    if (!fill) {
        // BasicStroke: width, cap, join, miter limit (dashed strokes arrive as fills)
        paint.setStyle(SkPaint::kStroke_Style);
        paint.setStrokeWidth(stroke[STROKE_WIDTH]);
        paint.setStrokeCap(STROKE_CAPS[std::clamp((int) stroke[STROKE_CAP], 0, (int) STROKE_CAPS.size() - 1)]);
        paint.setStrokeJoin(STROKE_JOINS[std::clamp((int) stroke[STROKE_JOIN], 0, (int) STROKE_JOINS.size() - 1)]);
        paint.setStrokeMiter(stroke[STROKE_MITER_LIMIT]);
    }
    // xform is an AffineTransform's flat matrix: m00 m10 m01 m11 m02 m12
    const SkMatrix matrix = SkMatrix::MakeAll(xform[0], xform[2], xform[4],
                                              xform[1], xform[3], xform[5],
                                              0, 0, 1);
    const bool identity = matrix.isIdentity();
    if (!identity) {
        // The paint (BufferedPaints) is in device space; keep it there while the
        // geometry is drawn under the user-space transform.
        SkMatrix inverse;
        if (paint.getShader() != nullptr && matrix.invert(&inverse)) {
            paint.setShader(paint.refShader()->makeWithLocalMatrix(inverse));
        }
        canvas->save();
        canvas->concat(matrix);
    }
    switch (kind) {
        case sun_java2d_skia_SKAnalyticShapes_KIND_RECT:
            canvas->drawRect(SkRect::MakeXYWH(params[SHAPE_X], params[SHAPE_Y], params[SHAPE_WIDTH], params[SHAPE_HEIGHT]),
                             paint);
            break;
        case sun_java2d_skia_SKAnalyticShapes_KIND_ROUND_RECT:
            // RoundRectangle2D's arc width and height are the corner ellipse's diameters; SkRRect takes its radii
            canvas->drawRRect(SkRRect::MakeRectXY(
                    SkRect::MakeXYWH(params[SHAPE_X], params[SHAPE_Y], params[SHAPE_WIDTH], params[SHAPE_HEIGHT]),
                    params[SHAPE_ARC_WIDTH] / 2, params[SHAPE_ARC_HEIGHT] / 2), paint);
            break;
        case sun_java2d_skia_SKAnalyticShapes_KIND_OVAL:
            canvas->drawOval(SkRect::MakeXYWH(params[SHAPE_X], params[SHAPE_Y], params[SHAPE_WIDTH], params[SHAPE_HEIGHT]),
                             paint);
            break;
        default: {   // KIND_PATH
            SkPath path = buildPath(windingRule, numTypes, types, coords);
            if (fill) {
                canvas->drawPath(path, paint);
            } else {
                // Stroke like Marlin does: the stroked outline is filled (dashed
                // strokes were dashed by Java's stroker and arrive as fills). Skia's
                // rasterizer would otherwise draw strokes of one device pixel or
                // less as hairlines with a different coverage rule.
                bool isFill = false;
                SkPath outline = skpathutils::FillPathWithPaint(path, paint, &isFill);
                SkPaint fillPaint = paint;
                fillPaint.setStyle(SkPaint::kFill_Style);
                canvas->drawPath(outline, fillPaint);
            }
            break;
        }
    }
    if (!identity) {
        canvas->restore();
    }
}
