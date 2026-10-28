/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.java2d.skia;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.PathIterator;

import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.pipe.ParallelogramPipe;
import sun.java2d.pipe.Region;
import sun.java2d.pipe.ShapeDrawPipe;
import sun.java2d.pipe.SpanIterator;

/**
 * Antialiased shapes on a {@link SKRasterSurfaceData}: the shape in device
 * space, the composite clip (a rectangle, or the clip region's spans) and the
 * colour go to Skia's raster backend, which fills the path into the image's
 * pixels on this thread. Strokes are Java's ({@link BasicStroke#createStrokedShape}),
 * so stroke geometry matches the loops; Skia's own stroker is a later rung.
 */
final class SKRasterShapePipe implements ShapeDrawPipe, ParallelogramPipe {

    private static final int WIND_EVEN_ODD = PathIterator.WIND_EVEN_ODD;

    /** Strokes Skia's stroker built, for the test of the mode. */
    static final java.util.concurrent.atomic.AtomicLong strokesBySkia = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Strokes as the GPU pipe does them: the device-space path normalized the way
     * Marlin normalizes strokes (unless STROKE_PURE), then Skia's stroker on the
     * user-space path under the transform — or, for dashes (Skia's dasher measures
     * curves differently) and in the comparison mode {@code sun.java2d.skia.strokes=java},
     * Java's stroker's outline filled.
     */
    @Override
    public void draw(SunGraphics2D sg2d, Shape s) {
        if (!(sg2d.stroke instanceof BasicStroke bs)) {
            boolean aa = antialiased(sg2d);
            fillDevicePath(sg2d, spanRulePath(sg2d, sg2d.stroke.createStrokedShape(s), aa), aa);
            return;
        }
        AffineTransform at = sg2d.transform;
        float width = (float) SKShapePipe.lineWidth(sg2d, bs);
        boolean aa = antialiased(sg2d);
        Shape userShape = s;
        if (sg2d.strokeHint != SunHints.INTVAL_STROKE_PURE) {
            Path2D.Float p = new Path2D.Float();
            p.append(new SKShapePipe.NormalizingIterator(s.getPathIterator(at), aa ? 0.5 : 0.25), false);
            try {
                p.transform(at.createInverse());
            } catch (NoninvertibleTransformException e) {
                return;   // nothing visible under a singular transform
            }
            userShape = p;
        }
        // non-antialiased strokes are Marlin's outline, as the loops' (Skia's stroker differs by whole pixels there)
        if (!aa || bs.getDashArray() != null || SKEnvironment.strokesByJava()) {
            BasicStroke outline = new BasicStroke(width, bs.getEndCap(), bs.getLineJoin(), bs.getMiterLimit(), bs.getDashArray(), bs.getDashPhase());
            Shape outlineShape = outline.createStrokedShape(userShape);
            // normalized above; the loops scan-convert the outline with the span iterator
            fillDevicePath(sg2d, aa ? new Path2D.Float(outlineShape, at) : forSpans(outlineShape.getPathIterator(at)), aa);
            return;
        }
        strokeUserPath(sg2d, new Path2D.Float(SKShapePipe.splitBentCubics(userShape, at)), at, width, bs, aa);
    }

    /**
     * Skia's non-antialiased scan conversion fills a pixel whose centre lies exactly on an
     * edge only on the edge's right or bottom side; the loops' span iterator and ProcessPath
     * fill it on the left or top side (left- and top-inclusive, half-open). A nudge of a 64th
     * of a pixel (Skia's edge precision) up and left lands the ties on the loops' side.
     */
    private static final double TIE_NUDGE = 1.0 / 64;

    /**
     * The device path of a non-antialiased fill under the loops' rule for it. An opaque fill under
     * a thin stroke state goes through ProcessPath, which samples the pixel's top-left corner (the
     * shape shifted half a pixel down and right under centre sampling; unshifted with STROKE_PURE);
     * any other fill, and every outline the loops fill for a stroke, goes through the span iterator,
     * which snaps the coordinates to quarter positions (unless STROKE_PURE) and samples centres.
     * Ties on edges shallower than 1:64 still resolve Skia's way (the nudge moves their crossings).
     */
    private static Path2D.Float nonAAFillPath(SunGraphics2D sg2d, Shape s) {
        boolean pure = sg2d.strokeHint == SunHints.INTVAL_STROKE_PURE;
        // ProcessPath: the loops' own fill of an opaque colour under a rectangular clip with a thin stroke;
        // a translucent composite, a paint or a shape clip take the span iterator (SurfaceData.validatePipe)
        boolean processPath = sg2d.strokeState == SunGraphics2D.STROKE_THIN && sg2d.compositeState <= SunGraphics2D.COMP_ISCOPY
                && sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR && sg2d.clipState != SunGraphics2D.CLIP_SHAPE;
        if (processPath) {
            Path2D.Float p = new Path2D.Float(s, sg2d.transform);
            double shift = (pure ? 0 : 0.5) - TIE_NUDGE;
            p.transform(AffineTransform.getTranslateInstance(shift, shift));
            return p;
        }
        return spanRulePath(sg2d, s, false);
    }

    /** The device path of a shape the loops' span iterator would fill: quarter-pixel normalization unless STROKE_PURE, then its curves flattened and ties nudged. */
    private static Path2D.Float spanRulePath(SunGraphics2D sg2d, Shape s, boolean aa) {
        if (aa) {
            return new Path2D.Float(s, sg2d.transform);
        }
        PathIterator pi = s.getPathIterator(sg2d.transform);
        return forSpans(sg2d.strokeHint == SunHints.INTVAL_STROKE_PURE ? pi : new SKShapePipe.NormalizingIterator(pi, 0.25));
    }

    /**
     * ShapeSpanIterator's chord tolerance: a curve whose control points lie within a pixel of its
     * chord is a line (MAX_FLAT_SQ); its ovals are visibly inside the curve Skia would follow.
     */
    private static final double SPAN_FLATNESS = 1.0;

    /** A device path as the loops' span iterator scan-converts it: curves flattened at its tolerance, ties nudged. */
    private static Path2D.Float forSpans(PathIterator devicePath) {
        Path2D.Float p = new Path2D.Float();
        p.append(new FlatteningPathIterator(devicePath, SPAN_FLATNESS, 10), false);
        p.transform(AffineTransform.getTranslateInstance(-TIE_NUDGE, -TIE_NUDGE));
        return p;
    }

    private static void strokeUserPath(SunGraphics2D sg2d, Path2D.Float path, AffineTransform at, float width, BasicStroke bs, boolean aa) {
        SKRasterSurfaceData sd = (SKRasterSurfaceData) sg2d.surfaceData;
        Region clip = sg2d.getCompClip();
        // the lock box: the path's device bounds grown by the stroke's reach (half width, miter or square-cap factor, device scale)
        Rectangle bounds = at.createTransformedShape(path).getBounds();
        double scale = Math.max(Math.abs(at.getScaleX()) + Math.abs(at.getShearX()), Math.abs(at.getShearY()) + Math.abs(at.getScaleY()));
        double reach = bs.getLineJoin() == BasicStroke.JOIN_MITER ? Math.max(bs.getMiterLimit(), 1.5) : 1.5;
        int pad = (int) Math.ceil(width / 2 * scale * reach) + 2;
        int cx1 = Math.max(clip.getLoX(), bounds.x - pad), cy1 = Math.max(clip.getLoY(), bounds.y - pad);
        int cx2 = Math.min(clip.getHiX(), bounds.x + bounds.width + pad), cy2 = Math.min(clip.getHiY(), bounds.y + bounds.height + pad);
        if (cx1 >= cx2 || cy1 >= cy2) {
            return;
        }
        PathData d = PathData.of(path);
        int[] colors = colorsFor(sg2d);
        float[] xform = {(float) at.getScaleX(), (float) at.getShearY(), (float) at.getShearX(), (float) at.getScaleY(), (float) at.getTranslateX(), (float) at.getTranslateY()};
        strokesBySkia.incrementAndGet();
        strokePath(sd, sd.pixelFormat(), sd.imageWidth(), sd.imageHeight(), d.types, d.typeCount, d.coords, d.coordCount, path.getWindingRule(),
                   xform, width, bs.getEndCap(), bs.getLineJoin(), bs.getMiterLimit(), aa, paintOf(sg2d, colors), colors, compositeRule(sg2d), cx1, cy1, cx2, cy2, spansOf(clip, cx1, cy1, cx2, cy2));
    }

    /** A path's segments as the native side reads them: types and coordinates. */
    private record PathData(byte[] types, int typeCount, float[] coords, int coordCount) {
        static PathData of(Path2D.Float path) {
            PathIterator pi = path.getPathIterator(null);
            byte[] types = new byte[32]; float[] coords = new float[128]; int nt = 0, nc = 0; float[] seg = new float[6];
            while (!pi.isDone()) {
                int t = pi.currentSegment(seg);
                if (nt == types.length) types = java.util.Arrays.copyOf(types, nt * 2);
                types[nt++] = (byte) t;
                int k = t == PathIterator.SEG_MOVETO || t == PathIterator.SEG_LINETO ? 2 : t == PathIterator.SEG_QUADTO ? 4 : t == PathIterator.SEG_CUBICTO ? 6 : 0;
                if (nc + k > coords.length) coords = java.util.Arrays.copyOf(coords, Math.max(nc + k, nc * 2));
                System.arraycopy(seg, 0, coords, nc, k); nc += k;
                pi.next();
            }
            return new PathData(types, nt, coords, nc);
        }
    }

    @Override
    public void fill(SunGraphics2D sg2d, Shape s) {
        boolean aa = antialiased(sg2d);
        fillDevicePath(sg2d, aa ? new Path2D.Float(s, sg2d.transform) : nonAAFillPath(sg2d, s), aa);
    }

    @Override
    public void fillParallelogram(SunGraphics2D sg2d, double ux1, double uy1, double ux2, double uy2,
                                  double x, double y, double dx1, double dy1, double dx2, double dy2) {
        if (dy1 == 0 && dx2 == 0) {   // axis-aligned: the converter's antialiased rectangles, by far the most frequent call
            fillDeviceRect(sg2d, (float) Math.min(x, x + dx1), (float) Math.min(y, y + dy2), (float) Math.max(x, x + dx1), (float) Math.max(y, y + dy2));
            return;
        }
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x, y); p.lineTo(x + dx1, y + dy1); p.lineTo(x + dx1 + dx2, y + dy1 + dy2); p.lineTo(x + dx2, y + dy2); p.closePath();
        fillDevicePath(sg2d, p, antialiased(sg2d));
    }

    @Override
    public void drawParallelogram(SunGraphics2D sg2d, double ux1, double uy1, double ux2, double uy2,
                                  double x, double y, double dx1, double dy1, double dx2, double dy2, double lw1, double lw2) {
        // the outer parallelogram grown by the line widths minus the inner one: even-odd fill
        double ldx1 = dx1 * lw1, ldy1 = dy1 * lw1, ldx2 = dx2 * lw2, ldy2 = dy2 * lw2;
        double ox = x - (ldx1 + ldx2) / 2, oy = y - (ldy1 + ldy2) / 2;
        double odx1 = dx1 + ldx1, ody1 = dy1 + ldy1, odx2 = dx2 + ldx2, ody2 = dy2 + ldy2;
        Path2D.Float p = new Path2D.Float(WIND_EVEN_ODD);
        p.moveTo(ox, oy); p.lineTo(ox + odx1, oy + ody1); p.lineTo(ox + odx1 + odx2, oy + ody1 + ody2); p.lineTo(ox + odx2, oy + ody2); p.closePath();
        double ix = x + (ldx1 + ldx2) / 2, iy = y + (ldy1 + ldy2) / 2;
        double idx1 = dx1 - ldx1, idy1 = dy1 - ldy1, idx2 = dx2 - ldx2, idy2 = dy2 - ldy2;
        if (idx1 * idy2 - idy1 * idx2 > 0 == dx1 * dy2 - dy1 * dx2 > 0) {   // the inner one still has the same orientation: a hole
            p.moveTo(ix, iy); p.lineTo(ix + idx1, iy + idy1); p.lineTo(ix + idx1 + idx2, iy + idy1 + idy2); p.lineTo(ix + idx2, iy + idy2); p.closePath();
        }
        fillDevicePath(sg2d, p, antialiased(sg2d));
    }

    private static void fillDeviceRect(SunGraphics2D sg2d, float x1, float y1, float x2, float y2) {
        SKRasterSurfaceData sd = (SKRasterSurfaceData) sg2d.surfaceData;
        Region clip = sg2d.getCompClip();
        int cx1 = Math.max(clip.getLoX(), (int) Math.floor(x1)), cy1 = Math.max(clip.getLoY(), (int) Math.floor(y1));
        int cx2 = Math.min(clip.getHiX(), (int) Math.ceil(x2)), cy2 = Math.min(clip.getHiY(), (int) Math.ceil(y2));
        if (cx1 >= cx2 || cy1 >= cy2) {
            return;
        }
        int[] colors = colorsFor(sg2d);
        fillRect(sd, sd.pixelFormat(), sd.imageWidth(), sd.imageHeight(), x1, y1, x2, y2, antialiased(sg2d), paintOf(sg2d, colors), colors, compositeRule(sg2d), cx1, cy1, cx2, cy2, spansOf(clip, cx1, cy1, cx2, cy2));
    }

    /** Non-antialiased shapes Skia scan-converted (the loops' ProcessPath and span iterator otherwise), for the test of the route. */
    static final java.util.concurrent.atomic.AtomicLong nonAntialiasedBySkia = new java.util.concurrent.atomic.AtomicLong();

    /** The graphics' antialiasing hint; a non-antialiased operation is counted here. */
    private static boolean antialiased(SunGraphics2D sg2d) {
        boolean aa = sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON;
        if (!aa) {
            nonAntialiasedBySkia.incrementAndGet();
        }
        return aa;
    }

    private static int compositeRule(SunGraphics2D sg2d) {
        return sg2d.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
    }

    /** Fills with a gradient paint Skia built, for the test of the route. */
    static final java.util.concurrent.atomic.AtomicLong paintsBySkia = new java.util.concurrent.atomic.AtomicLong();

    /** Whether the state's paint is a gradient the raster pipe renders as a Skia shader. */
    static boolean isGradient(SunGraphics2D sg2d) {
        return sg2d.paintState == SunGraphics2D.PAINT_GRADIENT || sg2d.paintState == SunGraphics2D.PAINT_LIN_GRADIENT
                || sg2d.paintState == SunGraphics2D.PAINT_RAD_GRADIENT;
    }

    // Paint descriptor kinds (the native side's switch)
    static final int PAINT_COLOR = 0, PAINT_GRADIENT = 1, PAINT_LINEAR = 2, PAINT_RADIAL = 3;

    /**
     * The paint for the native side: a solid colour (the colour with the
     * composite's extra alpha, {@code sg2d.eargb}), or a gradient in user space —
     * its geometry, stops and colours plus the graphics transform and the paint's
     * own, which the shader applies as its local matrix. Colours are ARGB; the
     * native side swizzles them for the N32 destination.
     */
    private static float[] paintOf(SunGraphics2D sg2d, int[] colorsOut) {
        AffineTransform at = sg2d.transform;
        float extraAlpha = sg2d.composite instanceof AlphaComposite ac ? ac.getAlpha() : 1f;
        float[] m = {(float) at.getScaleX(), (float) at.getShearY(), (float) at.getShearX(), (float) at.getScaleY(), (float) at.getTranslateX(), (float) at.getTranslateY()};
        switch (sg2d.paintState) {
            case SunGraphics2D.PAINT_GRADIENT -> {
                java.awt.GradientPaint gp = (java.awt.GradientPaint) sg2d.paint;
                java.awt.geom.Point2D p1 = gp.getPoint1(), p2 = gp.getPoint2();
                colorsOut[0] = gp.getColor1().getRGB(); colorsOut[1] = gp.getColor2().getRGB();
                paintsBySkia.incrementAndGet();
                return new float[] {PAINT_GRADIENT, extraAlpha, gp.isCyclic() ? 1 : 0, 0, 2, m[0], m[1], m[2], m[3], m[4], m[5], 1, 0, 0, 1, 0, 0,
                                    (float) p1.getX(), (float) p1.getY(), (float) p2.getX(), (float) p2.getY(), 0, 1};
            }
            case SunGraphics2D.PAINT_LIN_GRADIENT, SunGraphics2D.PAINT_RAD_GRADIENT -> {
                java.awt.MultipleGradientPaint mg = (java.awt.MultipleGradientPaint) sg2d.paint;
                java.awt.Color[] colors = mg.getColors(); float[] fractions = mg.getFractions();
                for (int i = 0; i < colors.length; i++) colorsOut[i] = colors[i].getRGB();
                AffineTransform gt = mg.getTransform();
                float[] g = {(float) gt.getScaleX(), (float) gt.getShearY(), (float) gt.getShearX(), (float) gt.getScaleY(), (float) gt.getTranslateX(), (float) gt.getTranslateY()};
                int cycle = mg.getCycleMethod().ordinal();   // NO_CYCLE 0, REFLECT 1, REPEAT 2, as the GPU pipe reads them
                int linear = mg.getColorSpace() == java.awt.MultipleGradientPaint.ColorSpaceType.LINEAR_RGB ? 1 : 0;
                float[] geo;
                int kind;
                if (sg2d.paintState == SunGraphics2D.PAINT_LIN_GRADIENT) {
                    java.awt.LinearGradientPaint lg = (java.awt.LinearGradientPaint) mg;
                    geo = new float[] {(float) lg.getStartPoint().getX(), (float) lg.getStartPoint().getY(), (float) lg.getEndPoint().getX(), (float) lg.getEndPoint().getY()};
                    kind = PAINT_LINEAR;
                } else {
                    java.awt.RadialGradientPaint rg = (java.awt.RadialGradientPaint) mg;
                    geo = new float[] {(float) rg.getCenterPoint().getX(), (float) rg.getCenterPoint().getY(), rg.getRadius(), (float) rg.getFocusPoint().getX(), (float) rg.getFocusPoint().getY()};
                    kind = PAINT_RADIAL;
                }
                float[] d = new float[17 + geo.length + fractions.length];
                d[0] = kind; d[1] = extraAlpha; d[2] = cycle; d[3] = linear; d[4] = colors.length;
                System.arraycopy(m, 0, d, 5, 6); System.arraycopy(g, 0, d, 11, 6);
                System.arraycopy(geo, 0, d, 17, geo.length); System.arraycopy(fractions, 0, d, 17 + geo.length, fractions.length);
                paintsBySkia.incrementAndGet();
                return d;
            }
            default -> {
                colorsOut[0] = sg2d.eargb;
                return new float[] {PAINT_COLOR, 1f, 0, 0, 1};
            }
        }
    }

    private static int[] colorsFor(SunGraphics2D sg2d) {
        return new int[sg2d.paint instanceof java.awt.MultipleGradientPaint mg ? mg.getColors().length : 2];
    }


    /** The clip region's spans within the box as (x1, y1, x2, y2) tuples, or null for a rectangular clip. */
    static int[] spansOf(Region clip, int cx1, int cy1, int cx2, int cy2) {
        if (clip.isRectangular()) {
            return null;
        }
        int[] box = {cx1, cy1, cx2, cy2};
        SpanIterator it = clip.getSpanIterator(box);
        int[] list = new int[64]; int n = 0;
        while (it.nextSpan(box)) {
            if (n + 4 > list.length) list = java.util.Arrays.copyOf(list, list.length * 2);
            list[n++] = box[0]; list[n++] = box[1]; list[n++] = box[2]; list[n++] = box[3];
            box[0] = cx1; box[1] = cy1; box[2] = cx2; box[3] = cy2;
        }
        return java.util.Arrays.copyOf(list, n);
    }

    private static void fillDevicePath(SunGraphics2D sg2d, Path2D.Float path, boolean aa) {
        SKRasterSurfaceData sd = (SKRasterSurfaceData) sg2d.surfaceData;
        Region clip = sg2d.getCompClip();
        Rectangle bounds = path.getBounds();
        int cx1 = Math.max(clip.getLoX(), bounds.x - 1), cy1 = Math.max(clip.getLoY(), bounds.y - 1);
        int cx2 = Math.min(clip.getHiX(), bounds.x + bounds.width + 2), cy2 = Math.min(clip.getHiY(), bounds.y + bounds.height + 2);
        if (cx1 >= cx2 || cy1 >= cy2) {
            return;
        }
        int[] spans = spansOf(clip, cx1, cy1, cx2, cy2);
        PathData d = PathData.of(path);
        int[] colors = colorsFor(sg2d);
        fillPath(sd, sd.pixelFormat(), sd.imageWidth(), sd.imageHeight(), d.types, d.typeCount, d.coords, d.coordCount, path.getWindingRule(), aa, paintOf(sg2d, colors), colors, compositeRule(sg2d), cx1, cy1, cx2, cy2, spans);
    }

    /** Strokes the user-space path under the transform with Skia's stroker (width, cap, join, miter limit as BasicStroke's). */
    private static native void strokePath(SKRasterSurfaceData sd, int pixelFormat, int width, int height, byte[] types, int typeCount,
                                          float[] coords, int coordCount, int windingRule, float[] xform, float lineWidth, int cap, int join, float miterLimit, boolean antialias,
                                          float[] paint, int[] colors, int compositeRule, int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);

    /** Fills the device-space rectangle into the surface's pixels: edges antialiased where fractional, or the pixels whose centre is inside. */
    private static native void fillRect(SKRasterSurfaceData sd, int pixelFormat, int width, int height, float x1, float y1, float x2, float y2, boolean antialias,
                                        float[] paint, int[] colors, int compositeRule, int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);

    /** Fills the device-space path into the surface's pixels: colour with its alpha, Porter-Duff rule, clip box and optional clip spans. */
    private static native void fillPath(SKRasterSurfaceData sd, int pixelFormat, int width, int height, byte[] types, int typeCount,
                                        float[] coords, int coordCount, int windingRule, boolean antialias, float[] paint, int[] colors, int compositeRule,
                                        int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);
}
