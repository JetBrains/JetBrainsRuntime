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
package sun.java2d.skia;

import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderingEngine;
import sun.java2d.pipe.ShapeDrawPipe;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RectangularShape;
import java.awt.geom.RoundRectangle2D;
import java.lang.annotation.Native;
import java.util.Arrays;

/**
 * Antialiased shapes Skia rasterizes analytically instead of Marlin's coverage
 * tiles, in the default {@code shapes=analytic} mode: rectangles, round
 * rectangles and ellipses under any transform, filled, or stroked with a
 * {@code BasicStroke} without dashes wider than a device pixel ({@link #isAnalytic}).
 * {@link HybridShapePipe} sends them here and every other shape to the Marlin pipe;
 * {@code -Dsun.java2d.skia.shapes=marlin} does not install it.
 * <p>
 * Geometry is prepared the way Marlin sees it, so that the remaining
 * differences are coverage computation only: with {@code STROKE_NORMALIZE}
 * (the default) the device-space edges of an axis-aligned stroke are snapped to
 * pixel centers, as Marlin's normalizing iterator snaps the end points of their
 * paths; fills are sent as they are. Strokes are outlined by Skia's stroker. A
 * degenerate fill (no area) is sent as its path, which Skia fills as Marlin
 * does: with nothing.
 */
final class SKAnalyticShapes {

    // Opcodes of this backend, above sun.java2d.pipe.BufferedOpCodes.
    @Native static final int FILL_SHAPE = 200;
    @Native static final int DRAW_SHAPE = 201;

    // The kinds of geometry an entry carries (SKRenderQueue.cpp reads them by these values).
    @Native static final int KIND_RECT = 0;
    @Native static final int KIND_ROUND_RECT = 1;
    @Native static final int KIND_OVAL = 2;
    @Native static final int KIND_PATH = 3;
    /** {@link #analyticKind}: a shape none of Skia's analytic renderers draws. */
    private static final int KIND_NONE = -1;

    /*
     * The entry's layout after the opcode and the kind: the transform, the stroke if
     * the shape is stroked, then the geometry (SKRenderQueue.cpp reads it in this order).
     */
    /** The transform: m00 m10 m01 m11 m02 m12, in {@code AffineTransform.getMatrix} order. */
    private static final int TRANSFORM_FLOATS = 6;
    /** The stroke: the line width, the end cap, the line join and the miter limit. */
    private static final int STROKE_FLOATS = 4;
    /** A rectangle, round rectangle or oval: x, y, width, height, arc width, arc height. */
    private static final int SHAPE_FLOATS = 6;
    /** A path's header: the winding rule, the segment count and the coordinate count. */
    private static final int PATH_HEADER_INTS = 3;

    /**
     * Device-space width of antialiased thin strokes: SunGraphics2D classifies
     * an AA stroke as thin only up to this width, and Marlin then strokes it
     * exactly this wide (DMarlinRenderingEngine.MIN_PEN_SIZE).
     */
    static final double THIN_PEN_SIZE = RenderingEngine.getInstance().getMinimumAAPenSize();

    /**
     * The device-space extent beyond which a shape is left to Marlin: Skia receives floats
     * (a shape out to 1e39 is gone, at 1e8 its edges are off by pixels), Marlin clips in
     * double precision. Up to 2^16 a float's spacing is at most 1/128 pixel, a fraction of a
     * coverage level at an edge; no window or image is that large.
     */
    static final double MAX_DEVICE_COORDINATE = 1 << 16;

    /** The transform types under which a shape's bounds are no longer its device-space bounds. */
    private static final int NON_AXIS_ALIGNED_TYPES =
            AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION | AffineTransform.TYPE_GENERAL_TRANSFORM;

    private final SKRenderQueue renderQueue;

    SKAnalyticShapes(SKRenderQueue renderQueue) {
        this.renderQueue = renderQueue;
    }

    /**
     * The {@code analytic} mode: shapes Skia renders analytically go to Skia,
     * everything else to the Marlin pipe that was installed for this state.
     */
    static final class HybridShapePipe implements ShapeDrawPipe {
        private final SKAnalyticShapes skia;
        private final ShapeDrawPipe marlin;

        HybridShapePipe(SKAnalyticShapes skia, ShapeDrawPipe marlin) {
            this.skia = skia;
            this.marlin = marlin;
        }

        @Override
        public void draw(SunGraphics2D sunGraphics2D, Shape shape) {
            if (sunGraphics2D.stroke instanceof BasicStroke basicStroke && isAnalytic(sunGraphics2D, shape, basicStroke)) {
                skia.render(sunGraphics2D, shape, basicStroke);
            } else {
                marlin.draw(sunGraphics2D, shape);
            }
        }

        @Override
        public void fill(SunGraphics2D sunGraphics2D, Shape shape) {
            if (isAnalytic(sunGraphics2D, shape, null)) {
                skia.render(sunGraphics2D, shape, null);
            } else {
                marlin.fill(sunGraphics2D, shape);
            }
        }
    }

    /**
     * The kind of analytic geometry a shape is: a rectangle, an oval or a round rectangle
     * (each a {@link RectangularShape}), or {@link #KIND_NONE} for any other shape.
     */
    private static int analyticKind(Shape shape) {
        return switch (shape) {
            case Rectangle2D _ -> KIND_RECT;
            case Ellipse2D _ -> KIND_OVAL;
            case RoundRectangle2D _ -> KIND_ROUND_RECT;
            default -> KIND_NONE;
        };
    }

    /**
     * Whether the shape reaches one of Skia's analytic renderers: rectangles,
     * ellipses and round rectangles, filled, or stroked where Skia's result can
     * match Java's, within {@link #MAX_DEVICE_COORDINATE} of the origin in device
     * space. Dashes are measured along Java's cubic ellipse, not Skia's conic
     * one, and Skia's analytic oval stroke degrades at one device pixel or less.
     *
     * @param basicStroke the stroke, or {@code null} for a fill
     */
    static boolean isAnalytic(SunGraphics2D sunGraphics2D, Shape shape, BasicStroke basicStroke) {
        if (analyticKind(shape) == KIND_NONE) {
            return false;
        }
        if (shape instanceof RoundRectangle2D roundRectangle
                && (!Double.isFinite(roundRectangle.getArcWidth()) || !Double.isFinite(roundRectangle.getArcHeight()))) {
            return false;
        }
        RectangularShape rectangular = (RectangularShape) shape;
        double x = rectangular.getX(), y = rectangular.getY();
        double width = rectangular.getWidth(), height = rectangular.getHeight();
        AffineTransform transform = sunGraphics2D.transform;
        double scale = Math.sqrt(Math.abs(transform.getDeterminant()));
        double reach = 0;   // how far a stroke reaches beyond the bounds, in device space
        if (basicStroke != null) {
            double deviceWidth = lineWidth(sunGraphics2D, basicStroke) * scale;
            if (basicStroke.getDashArray() != null || !(deviceWidth > 1.0)) {
                return false;
            }
            reach = deviceWidth;
        }
        double[] corners = { x, y, x + width, y, x, y + height, x + width, y + height };
        transform.transform(corners, 0, corners, 0, 4);
        for (double corner : corners) {
            if (!(Math.abs(corner) + reach <= MAX_DEVICE_COORDINATE)) {   // also NaN and infinity
                return false;
            }
        }
        return true;
    }

    /** The stroke's width in user space; thin strokes are one device pixel wide. */
    static double lineWidth(SunGraphics2D sunGraphics2D, BasicStroke basicStroke) {
        return sunGraphics2D.strokeState <= SunGraphics2D.STROKE_THINDASHED
                ? thinLineWidth(sunGraphics2D.transform) : basicStroke.getLineWidth();
    }

    /** Marlin's thin-stroke rule: {@link #THIN_PEN_SIZE} device pixels expressed in user space. */
    static double thinLineWidth(AffineTransform transform) {
        double scale = Math.sqrt(Math.abs(transform.getDeterminant()));
        return scale > 0 ? THIN_PEN_SIZE / scale : THIN_PEN_SIZE;
    }

    /** One shape, prepared for the queue. */
    private static final class Geometry {
        int kind;
        float[] transform = IDENTITY;      // TRANSFORM_FLOATS (AffineTransform.getMatrix order)
        float[] shapeParameters;           // SHAPE_FLOATS: x y width height, arc width and height of a round rect
        int windingRule;
        int[] segmentTypes;                // path segment types
        float[] coordinates;               // path coordinates
        int segmentCount, coordinateCount;
    }

    private static final float[] IDENTITY = { 1, 0, 0, 1, 0, 0 };

    /**
     * A shape {@link #isAnalytic} accepted: filled or stroked.
     *
     * @param basicStroke the stroke, or {@code null} for a fill
     */
    private void render(SunGraphics2D sunGraphics2D, Shape shape, BasicStroke basicStroke) {
        AffineTransform transform = sunGraphics2D.transform;
        boolean stroked = basicStroke != null;
        // Java normalizes strokes only (AAShapePipe.renderPath: adjust = bs != null
        // && !PURE): the outline of an antialiased fill is rasterized where it is,
        // so an edge on a pixel boundary is crisp and a fractional edge covers
        // the outer pixel by its fraction. Snapping fill edges to pixel centers
        // spilled half a device pixel over the right and bottom of every
        // rounded panel and tab (AnalyticShapeEdgeTest).
        boolean normalize = stroked && sunGraphics2D.strokeHint != SunHints.INTVAL_STROKE_PURE;
        boolean axisAligned = (transform.getType() & NON_AXIS_ALIGNED_TYPES) == 0;
        Geometry geometry = new Geometry();
        float[] stroke = null;   // STROKE_FLOATS
        if (stroked) {
            stroke = new float[] { (float) lineWidth(sunGraphics2D, basicStroke), basicStroke.getEndCap(),
                                   basicStroke.getLineJoin(), basicStroke.getMiterLimit() };
        }
        if (!analytic(shape, transform, axisAligned && normalize, axisAligned, stroked, geometry)) {
            if (stroked) {
                return;   // a stroke under a singular transform: nothing visible
            }
            // a degenerate fill: its device-space path, which Skia fills with nothing as Marlin does
            path(shape.getPathIterator(transform), geometry);
        }
        enqueue(sunGraphics2D, !stroked, geometry, stroke);
    }

    /**
     * Rectangles, round rectangles and ellipses reach Skia's analytic renderers
     * under any transform. Axis-aligned: the bounds go through the transform
     * and, with normalization, their edges are snapped to pixel centers, which
     * is what the normalizing iterator does to the end points of their paths;
     * fills are sent in device space, strokes mapped back to user space and
     * stroked under the transform. Rotated or sheared: the shape is sent in
     * user space under the transform, without normalization (Marlin would snap
     * the path's end points in device space; the shape then is no longer
     * analytic). False for a degenerate fill and a stroke under a singular transform.
     */
    private static boolean analytic(Shape shape, AffineTransform transform, boolean normalize, boolean axisAligned,
                                    boolean stroked, Geometry geometry) {
        int kind = analyticKind(shape);
        if (kind == KIND_NONE) {
            return false;
        }
        geometry.kind = kind;
        RectangularShape rectangular = (RectangularShape) shape;
        double x = rectangular.getX(), y = rectangular.getY();
        double width = rectangular.getWidth(), height = rectangular.getHeight();
        double arcWidth = 0, arcHeight = 0;
        if (shape instanceof RoundRectangle2D roundRectangle) {
            // Java clamps each arc to its own side (RoundRectIterator): arcs wider
            // than the shape make a pill with semicircular ends. Skia's rounded
            // rect scales both radii by one factor instead, which flattens the
            // ends of a pill (60 levels and more at the arcs).
            arcWidth = Math.min(width, Math.abs(roundRectangle.getArcWidth()));
            arcHeight = Math.min(height, Math.abs(roundRectangle.getArcHeight()));
        }
        if (!(width > 0 && height > 0) && !stroked) {
            return false;   // degenerate fill
        }
        if (!axisAligned) {
            geometry.shapeParameters = new float[] { (float) x, (float) y, (float) width, (float) height,
                                           (float) arcWidth, (float) arcHeight };
            geometry.transform = matrixOf(transform);
            return true;
        }
        double[] deviceCorners = { x, y, x + width, y + height };
        transform.transform(deviceCorners, 0, deviceCorners, 0, 2);
        double left = Math.min(deviceCorners[0], deviceCorners[2]), top = Math.min(deviceCorners[1], deviceCorners[3]);
        double right = Math.max(deviceCorners[0], deviceCorners[2]), bottom = Math.max(deviceCorners[1], deviceCorners[3]);
        if (normalize) {
            left = Math.floor(left) + 0.5; top = Math.floor(top) + 0.5;
            right = Math.floor(right) + 0.5; bottom = Math.floor(bottom) + 0.5;
        }
        double scaleX = Math.abs(transform.getScaleX()), scaleY = Math.abs(transform.getScaleY());
        if (!stroked) {
            geometry.shapeParameters = new float[] { (float) left, (float) top, (float) (right - left), (float) (bottom - top),
                                           (float) (arcWidth * scaleX), (float) (arcHeight * scaleY) };
            return true;
        }
        // back to user space for stroking
        double[] userCorners = { left, top, right, bottom };
        try {
            transform.createInverse().transform(userCorners, 0, userCorners, 0, 2);
        } catch (NoninvertibleTransformException exception) {
            return false;
        }
        double userLeft = Math.min(userCorners[0], userCorners[2]), userTop = Math.min(userCorners[1], userCorners[3]);
        double userRight = Math.max(userCorners[0], userCorners[2]), userBottom = Math.max(userCorners[1], userCorners[3]);
        geometry.shapeParameters = new float[] { (float) userLeft, (float) userTop, (float) (userRight - userLeft),
                                       (float) (userBottom - userTop), (float) arcWidth, (float) arcHeight };
        geometry.transform = matrixOf(transform);
        return true;
    }

    private static float[] matrixOf(AffineTransform transform) {
        double[] matrix = new double[TRANSFORM_FLOATS];
        transform.getMatrix(matrix);
        return new float[] { (float) matrix[0], (float) matrix[1], (float) matrix[2],
                             (float) matrix[3], (float) matrix[4], (float) matrix[5] };
    }

    /**
     * The initial room for a path's segments and coordinates, doubled when it runs out: enough
     * for any shape that gets here (a round rectangle's path has 10 segments and 34 coordinates).
     */
    private static final int INITIAL_SEGMENTS = 16;
    private static final int INITIAL_COORDINATES = 64;
    /** {@code PathIterator.currentSegment} writes at most a cubic's three points. */
    private static final int MAX_SEGMENT_COORDINATES = 6;

    private static void path(PathIterator pathIterator, Geometry geometry) {
        geometry.kind = KIND_PATH;
        geometry.windingRule = pathIterator.getWindingRule();
        int[] segmentTypes = new int[INITIAL_SEGMENTS];
        float[] coordinates = new float[INITIAL_COORDINATES];
        float[] segment = new float[MAX_SEGMENT_COORDINATES];
        int segmentCount = 0, coordinateCount = 0;
        for (; !pathIterator.isDone(); pathIterator.next()) {
            int type = pathIterator.currentSegment(segment);
            int segmentCoordinates = switch (type) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            if (segmentCount == segmentTypes.length) {
                segmentTypes = Arrays.copyOf(segmentTypes, segmentCount * 2);
            }
            if (coordinateCount + segmentCoordinates > coordinates.length) {
                coordinates = Arrays.copyOf(coordinates, Math.max(coordinates.length * 2, coordinateCount + segmentCoordinates));
            }
            segmentTypes[segmentCount++] = type;
            System.arraycopy(segment, 0, coordinates, coordinateCount, segmentCoordinates);
            coordinateCount += segmentCoordinates;
        }
        geometry.segmentTypes = segmentTypes;
        geometry.segmentCount = segmentCount;
        geometry.coordinates = coordinates;
        geometry.coordinateCount = coordinateCount;
    }

    /**
     * @param stroke the stroke's {@link #STROKE_FLOATS}, or {@code null} for a fill
     */
    private void enqueue(SunGraphics2D sunGraphics2D, boolean fill, Geometry geometry, float[] stroke) {
        renderQueue.lock();
        try {
            SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
            SKContext.validateContext(destination, destination, sunGraphics2D.getCompClip(), sunGraphics2D.composite,
                                      null, sunGraphics2D.paint, sunGraphics2D, SKContext.NO_CONTEXT_FLAGS);
            // the opcode and the kind, the transform, the stroke, then the geometry;
            // at most a round rectangle's path: far below the buffer's capacity
            int headerBytes = 2 * Integer.BYTES + TRANSFORM_FLOATS * Float.BYTES;
            int strokeBytes = stroke != null ? STROKE_FLOATS * Float.BYTES : 0;
            int geometryBytes = geometry.kind == KIND_PATH
                    ? (PATH_HEADER_INTS + geometry.segmentCount) * Integer.BYTES + geometry.coordinateCount * Float.BYTES
                    : SHAPE_FLOATS * Float.BYTES;
            renderQueue.ensureCapacity(headerBytes + strokeBytes + geometryBytes);
            RenderBuffer buffer = renderQueue.getBuffer();
            buffer.putInt(fill ? FILL_SHAPE : DRAW_SHAPE);
            buffer.putInt(geometry.kind);
            buffer.put(geometry.transform, 0, TRANSFORM_FLOATS);
            if (stroke != null) {
                buffer.put(stroke, 0, STROKE_FLOATS);
            }
            if (geometry.kind == KIND_PATH) {
                buffer.putInt(geometry.windingRule);
                buffer.putInt(geometry.segmentCount);
                buffer.putInt(geometry.coordinateCount);
                buffer.put(geometry.segmentTypes, 0, geometry.segmentCount);
                buffer.put(geometry.coordinates, 0, geometry.coordinateCount);
            } else {
                buffer.put(geometry.shapeParameters, 0, SHAPE_FLOATS);
            }
        } finally {
            renderQueue.unlock();
        }
    }
}
