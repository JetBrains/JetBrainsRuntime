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
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;

/**
 * Antialiased shapes rasterized by Skia instead of Marlin's coverage tiles:
 * all of them with {@code -Dsun.java2d.skia.shapes=skia}, by default only the
 * ones Skia renders analytically (rectangles, round rectangles and ellipses
 * under any transform, see {@link Hybrid}); everything else is a path.
 * <p>
 * Geometry is prepared the way Marlin sees it, so that the remaining
 * differences are coverage computation only. Strokes are normalized: with
 * {@code STROKE_NORMALIZE} (the default) the device-space path end points are
 * snapped to pixel centres and the control points shifted along, as Marlin's
 * normalizing iterator does for antialiased rendering, and the path is mapped
 * back to user space because {@code BasicStroke} applies under the transform.
 * Fills are sent in device space as they are. With
 * {@code -Dsun.java2d.skia.strokes=java} every stroke is outlined by Java's
 * stroker and the outline filled, as dashed strokes always are.
 */
final class SKShapePipe implements ShapeDrawPipe {

    // Opcodes of this backend, above sun.java2d.pipe.BufferedOpCodes.
    static final int FILL_SHAPE = 200;
    static final int DRAW_SHAPE = 201;

    static final int KIND_RECT = 0;
    static final int KIND_ROUND_RECT = 1;
    static final int KIND_OVAL = 2;
    static final int KIND_PATH = 3;

    /**
     * Device-space width of antialiased thin strokes: SunGraphics2D classifies
     * an AA stroke as thin only up to this width, and Marlin then strokes it
     * exactly this wide (DMarlinRenderingEngine.MIN_PEN_SIZE).
     */
    private static final double THIN_PEN_SIZE = RenderingEngine.getInstance().getMinimumAAPenSize();

    private static final int AXIS_ALIGNED_MASK =
            AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION | AffineTransform.TYPE_GENERAL_TRANSFORM;

    private final SKRenderQueue rq;

    SKShapePipe(SKRenderQueue rq) {
        this.rq = rq;
    }

    @Override
    public void draw(SunGraphics2D sg2d, Shape s) {
        if (sg2d.stroke instanceof BasicStroke bs) {
            render(sg2d, s, bs);
        } else {
            fill(sg2d, sg2d.stroke.createStrokedShape(s));
        }
    }

    /**
     * The {@code analytic} mode: shapes Skia renders analytically go to Skia,
     * everything else to the Marlin pipe that was installed for this state.
     */
    static final class Hybrid implements ShapeDrawPipe {
        private final SKShapePipe skia;
        private final ShapeDrawPipe marlin;

        Hybrid(SKShapePipe skia, ShapeDrawPipe marlin) {
            this.skia = skia;
            this.marlin = marlin;
        }

        @Override
        public void draw(SunGraphics2D sg2d, Shape s) {
            if (sg2d.stroke instanceof BasicStroke bs && isAnalytic(sg2d, s, bs)) {
                skia.draw(sg2d, s);
            } else {
                marlin.draw(sg2d, s);
            }
        }

        @Override
        public void fill(SunGraphics2D sg2d, Shape s) {
            if (isAnalytic(sg2d, s, null)) {
                skia.fill(sg2d, s);
            } else {
                marlin.fill(sg2d, s);
            }
        }
    }

    /**
     * Whether {@link #render} would reach one of Skia's analytic renderers:
     * rectangles, ellipses and round rectangles, filled, or stroked where
     * Skia's result can match Java's. Dashes are measured along Java's cubic
     * ellipse, not Skia's conic one, and Skia's analytic oval stroke degrades
     * at one device pixel or less.
     */
    static boolean isAnalytic(SunGraphics2D sg2d, Shape s, BasicStroke bs) {
        if (!(s instanceof Rectangle2D || s instanceof Ellipse2D || s instanceof RoundRectangle2D)) {
            return false;
        }
        AffineTransform at = sg2d.transform;
        if (bs == null) {
            return true;
        }
        return bs.getDashArray() == null && lineWidth(sg2d, bs) * Math.sqrt(Math.abs(at.getDeterminant())) > 1.0;
    }

    /** The stroke's width in user space; thin strokes are one device pixel wide. */
    private static double lineWidth(SunGraphics2D sg2d, BasicStroke bs) {
        return sg2d.strokeState <= SunGraphics2D.STROKE_THINDASHED
                ? userSpaceLineWidth(sg2d.transform, THIN_PEN_SIZE) : bs.getLineWidth();
    }

    @Override
    public void fill(SunGraphics2D sg2d, Shape s) {
        render(sg2d, s, null);
    }

    /** One shape, prepared for the queue. */
    private static final class Geometry {
        int kind;
        float[] xform = IDENTITY;          // m00 m10 m01 m11 m02 m12 (AffineTransform order)
        float[] params;                    // rect/oval: x y w h; round rect: + arcw arch
        int windingRule;
        int[] types;                       // path segment types
        float[] coords;                    // path coordinates
        int numTypes, numCoords;
    }

    private static final float[] IDENTITY = { 1, 0, 0, 1, 0, 0 };

    private void render(SunGraphics2D sg2d, Shape s, BasicStroke bs) {
        AffineTransform at = sg2d.transform;
        // Java normalizes strokes only (AAShapePipe.renderPath: adjust = bs != null
        // && !PURE): the outline of an antialiased fill is rasterized where it is,
        // so an edge on a pixel boundary is crisp and a fractional edge covers
        // the outer pixel by its fraction. Snapping fill edges to pixel centres
        // spilled half a device pixel over the right and bottom of every
        // rounded panel and tab (AnalyticShapeEdgeTest).
        boolean normalize = bs != null && sg2d.strokeHint != SunHints.INTVAL_STROKE_PURE;
        boolean axisAligned = (at.getType() & AXIS_ALIGNED_MASK) == 0;
        Geometry geo = new Geometry();
        float[] stroke = null;   // width, cap, join, miter limit; dashes never reach the native side
        if (bs != null) {
            stroke = new float[] { (float) lineWidth(sg2d, bs), bs.getEndCap(), bs.getLineJoin(), bs.getMiterLimit() };
        }
        boolean analyticStroke = bs == null || !SKEnvironment.strokesByJava();   // Java's stroker outlines instead
        if (analyticStroke && isAnalytic(sg2d, s, bs) && analytic(s, at, axisAligned && normalize, axisAligned, bs != null, geo)) {
            // ready
        } else if (bs == null) {
            // fill: the device-space path as it is (no normalization for fills)
            path(s.getPathIterator(at), geo);
        } else {
            // stroke: normalized in device space, stroked in user space
            Shape userShape = s;
            if (normalize) {
                Path2D.Float p = new Path2D.Float();
                p.append(new NormalizingIterator(s.getPathIterator(at)), false);
                try {
                    p.transform(at.createInverse());
                } catch (NoninvertibleTransformException e) {
                    return;   // nothing visible under a singular transform
                }
                userShape = p;
            }
            if (bs.getDashArray() != null || SKEnvironment.strokesByJava()) {
                // Dashes are measured by Java's own stroker: Skia's dasher measures
                // curves differently and the dash ends drift along the path. Skia
                // only fills the outline, in device space. The same route for
                // every stroke is the comparison mode sun.java2d.skia.strokes=java.
                BasicStroke outline = new BasicStroke(stroke[0], bs.getEndCap(), bs.getLineJoin(), bs.getMiterLimit(),
                                                      bs.getDashArray(), bs.getDashPhase());
                path(outline.createStrokedShape(userShape).getPathIterator(at), geo);
                enqueue(sg2d, true, geo, null);
                return;
            }
            path(userShape.getPathIterator(null), geo);
            geo.xform = matrix(at);
        }
        enqueue(sg2d, bs == null, geo, stroke);
    }

    /**
     * Rectangles, round rectangles and ellipses reach Skia's analytic renderers
     * under any transform. Axis-aligned: the bounds go through the transform
     * and, with normalization, their edges are snapped to pixel centres, which
     * is what the normalizing iterator does to the end points of their paths;
     * fills are sent in device space, strokes mapped back to user space and
     * stroked under the transform. Rotated or sheared: the shape is sent in
     * user space under the transform, without normalization (Marlin would snap
     * the path's end points in device space; the shape then is no longer
     * analytic).
     */
    private static boolean analytic(Shape s, AffineTransform at, boolean normalize, boolean axisAligned,
                                    boolean stroked, Geometry geo) {
        double x, y, w, h, aw = 0, ah = 0;
        if (s instanceof Rectangle2D r) {
            geo.kind = KIND_RECT;
            x = r.getX(); y = r.getY(); w = r.getWidth(); h = r.getHeight();
        } else if (s instanceof Ellipse2D e) {
            geo.kind = KIND_OVAL;
            x = e.getX(); y = e.getY(); w = e.getWidth(); h = e.getHeight();
        } else if (s instanceof RoundRectangle2D rr) {
            geo.kind = KIND_ROUND_RECT;
            x = rr.getX(); y = rr.getY(); w = rr.getWidth(); h = rr.getHeight();
            // Java clamps each arc to its own side (RoundRectIterator): arcs wider
            // than the shape make a pill with semicircular ends. Skia's rounded
            // rect scales both radii by one factor instead, which flattens the
            // ends of a pill (60 levels and more at the arcs).
            aw = Math.min(w, Math.abs(rr.getArcWidth())); ah = Math.min(h, Math.abs(rr.getArcHeight()));
        } else {
            return false;
        }
        if (!(w > 0 && h > 0)) {
            if (!stroked) {
                return false;   // degenerate fill: let the path route decide (draws nothing)
            }
        }
        if (!axisAligned) {
            geo.params = new float[] { (float) x, (float) y, (float) w, (float) h, (float) aw, (float) ah };
            geo.xform = matrix(at);
            return true;
        }
        double[] pts = { x, y, x + w, y + h };
        at.transform(pts, 0, pts, 0, 2);
        double l = Math.min(pts[0], pts[2]), t = Math.min(pts[1], pts[3]);
        double r = Math.max(pts[0], pts[2]), b = Math.max(pts[1], pts[3]);
        if (normalize) {
            l = Math.floor(l) + 0.5; t = Math.floor(t) + 0.5;
            r = Math.floor(r) + 0.5; b = Math.floor(b) + 0.5;
        }
        double sx = Math.abs(at.getScaleX()), sy = Math.abs(at.getScaleY());
        if (!stroked) {
            geo.params = new float[] { (float) l, (float) t, (float) (r - l), (float) (b - t), (float) (aw * sx), (float) (ah * sy) };
            return true;
        }
        // back to user space for stroking
        double[] back = { l, t, r, b };
        try {
            at.createInverse().transform(back, 0, back, 0, 2);
        } catch (NoninvertibleTransformException e) {
            return false;
        }
        double ul = Math.min(back[0], back[2]), ut = Math.min(back[1], back[3]);
        double ur = Math.max(back[0], back[2]), ub = Math.max(back[1], back[3]);
        geo.params = new float[] { (float) ul, (float) ut, (float) (ur - ul), (float) (ub - ut), (float) aw, (float) ah };
        geo.xform = matrix(at);
        return true;
    }

    private static float[] matrix(AffineTransform at) {
        double[] m = new double[6];
        at.getMatrix(m);
        return new float[] { (float) m[0], (float) m[1], (float) m[2], (float) m[3], (float) m[4], (float) m[5] };
    }

    /** Marlin's thin-stroke rule: a device-space width expressed in user space. */
    private static double userSpaceLineWidth(AffineTransform at, double deviceWidth) {
        double scale = Math.sqrt(Math.abs(at.getDeterminant()));
        return scale > 0 ? deviceWidth / scale : deviceWidth;
    }

    private static void path(PathIterator pi, Geometry geo) {
        geo.kind = KIND_PATH;
        geo.windingRule = pi.getWindingRule();
        int[] types = new int[64];
        float[] coords = new float[256];
        float[] seg = new float[6];
        int nt = 0, nc = 0;
        for (; !pi.isDone(); pi.next()) {
            int type = pi.currentSegment(seg);
            int n = switch (type) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            if (nt == types.length) {
                types = Arrays.copyOf(types, nt * 2);
            }
            if (nc + n > coords.length) {
                coords = Arrays.copyOf(coords, Math.max(coords.length * 2, nc + n));
            }
            types[nt++] = type;
            System.arraycopy(seg, 0, coords, nc, n);
            nc += n;
        }
        geo.types = types;
        geo.numTypes = nt;
        geo.coords = coords;
        geo.numCoords = nc;
    }

    /**
     * DMarlinRenderingEngine's normalizing iterator for antialiased rendering
     * (NearestPixelCenter): end points move to the nearest pixel centre, control
     * points by the same amounts as the end points they belong to.
     */
    private static final class NormalizingIterator implements PathIterator {
        private final PathIterator src;
        private double curXAdjust, curYAdjust, moveXAdjust, moveYAdjust;
        private final double[] tmp = new double[6];

        NormalizingIterator(PathIterator src) {
            this.src = src;
        }

        private static double normCoord(double v) {
            return Math.floor(v) + 0.5;
        }

        @Override
        public int currentSegment(double[] coords) {
            int type = src.currentSegment(coords);
            int last;
            switch (type) {
                case SEG_MOVETO, SEG_LINETO -> last = 0;
                case SEG_QUADTO -> last = 2;
                case SEG_CUBICTO -> last = 4;
                default -> {
                    curXAdjust = moveXAdjust;
                    curYAdjust = moveYAdjust;
                    return type;
                }
            }
            double c = coords[last];
            double xAdjust = normCoord(c);
            coords[last] = xAdjust;
            xAdjust -= c;
            c = coords[last + 1];
            double yAdjust = normCoord(c);
            coords[last + 1] = yAdjust;
            yAdjust -= c;
            switch (type) {
                case SEG_MOVETO -> {
                    moveXAdjust = xAdjust;
                    moveYAdjust = yAdjust;
                }
                case SEG_QUADTO -> {
                    coords[0] += (curXAdjust + xAdjust) / 2.0;
                    coords[1] += (curYAdjust + yAdjust) / 2.0;
                }
                case SEG_CUBICTO -> {
                    coords[0] += curXAdjust;
                    coords[1] += curYAdjust;
                    coords[2] += xAdjust;
                    coords[3] += yAdjust;
                }
                default -> { }
            }
            curXAdjust = xAdjust;
            curYAdjust = yAdjust;
            return type;
        }

        @Override
        public int currentSegment(float[] coords) {
            int type = currentSegment(tmp);
            for (int i = 0; i < 6; i++) {
                coords[i] = (float) tmp[i];
            }
            return type;
        }

        @Override public int getWindingRule() { return src.getWindingRule(); }
        @Override public boolean isDone() { return src.isDone(); }
        @Override public void next() { src.next(); }
    }

    private void enqueue(SunGraphics2D sg2d, boolean fill, Geometry geo, float[] stroke) {
        rq.lock();
        try {
            SKSurfaceData dstData = SurfaceData.convertTo(SKSurfaceData.class, sg2d.surfaceData);
            SKContext.validateContext(dstData, dstData, sg2d.getCompClip(), sg2d.composite,
                                      null, sg2d.paint, sg2d, SKContext.NO_CONTEXT_FLAGS);
            int strokeInts = stroke != null ? stroke.length : 0;
            int geometryInts = geo.kind == KIND_PATH ? 3 + geo.numTypes + geo.numCoords : 6;
            int bytes = 4 * (2 + 6 + strokeInts + geometryInts);
            RenderBuffer buf = rq.getBuffer();
            if (bytes <= buf.capacity()) {
                rq.ensureCapacity(bytes);
                buf.putInt(fill ? FILL_SHAPE : DRAW_SHAPE);
                buf.putInt(geo.kind);
                buf.put(geo.xform, 0, 6);
                if (stroke != null) {
                    buf.put(stroke, 0, stroke.length);
                }
                if (geo.kind == KIND_PATH) {
                    buf.putInt(geo.windingRule);
                    buf.putInt(geo.numTypes);
                    buf.putInt(geo.numCoords);
                    buf.put(geo.types, 0, geo.numTypes);
                    buf.put(geo.coords, 0, geo.numCoords);
                } else {
                    buf.put(geo.params, 0, 6);
                }
            } else {
                // a path too large for the queue (the other kinds always fit): draw
                // directly on the flusher thread
                rq.flushAndInvokeNow(() -> drawShape(fill, geo.kind, geo.xform, stroke, geo.windingRule,
                                                     geo.types, geo.numTypes, geo.coords, geo.numCoords));
            }
        } finally {
            rq.unlock();
        }
    }

    private static native void drawShape(boolean fill, int kind, float[] xform, float[] stroke,
                                         int windingRule, int[] types, int numTypes, float[] coords, int numCoords);
}
