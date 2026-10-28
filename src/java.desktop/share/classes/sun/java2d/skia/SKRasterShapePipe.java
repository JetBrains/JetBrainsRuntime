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
import java.awt.geom.PathIterator;

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

    @Override
    public void draw(SunGraphics2D sg2d, Shape s) {
        Shape stroked;
        if (sg2d.strokeState == SunGraphics2D.STROKE_THIN) {
            // one device pixel wide, like the loops' thin strokes: stroke after the transform
            stroked = new BasicStroke(1f).createStrokedShape(sg2d.transform.createTransformedShape(s));
            fillDevicePath(sg2d, new Path2D.Float(stroked));
        } else {
            stroked = sg2d.stroke.createStrokedShape(s);
            fillDevicePath(sg2d, new Path2D.Float(stroked, sg2d.transform));
        }
    }

    @Override
    public void fill(SunGraphics2D sg2d, Shape s) {
        fillDevicePath(sg2d, new Path2D.Float(s, sg2d.transform));
    }

    @Override
    public void fillParallelogram(SunGraphics2D sg2d, double ux1, double uy1, double ux2, double uy2,
                                  double x, double y, double dx1, double dy1, double dx2, double dy2) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x, y); p.lineTo(x + dx1, y + dy1); p.lineTo(x + dx1 + dx2, y + dy1 + dy2); p.lineTo(x + dx2, y + dy2); p.closePath();
        fillDevicePath(sg2d, p);
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
        fillDevicePath(sg2d, p);
    }

    private static void fillDevicePath(SunGraphics2D sg2d, Path2D.Float path) {
        SKRasterSurfaceData sd = (SKRasterSurfaceData) sg2d.surfaceData;
        Region clip = sg2d.getCompClip();
        Rectangle bounds = path.getBounds();
        int cx1 = Math.max(clip.getLoX(), bounds.x - 1), cy1 = Math.max(clip.getLoY(), bounds.y - 1);
        int cx2 = Math.min(clip.getHiX(), bounds.x + bounds.width + 2), cy2 = Math.min(clip.getHiY(), bounds.y + bounds.height + 2);
        if (cx1 >= cx2 || cy1 >= cy2) {
            return;
        }
        int[] spans = null;
        if (!clip.isRectangular()) {
            int[] box = {cx1, cy1, cx2, cy2};
            SpanIterator it = clip.getSpanIterator(box);
            int[] list = new int[64]; int n = 0;
            while (it.nextSpan(box)) {
                if (n + 4 > list.length) list = java.util.Arrays.copyOf(list, list.length * 2);
                list[n++] = box[0]; list[n++] = box[1]; list[n++] = box[2]; list[n++] = box[3];
                box[0] = cx1; box[1] = cy1; box[2] = cx2; box[3] = cy2;
            }
            spans = java.util.Arrays.copyOf(list, n);
        }
        // the path's segments as the native side reads them: types and coordinates in device space
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
        int rule = sg2d.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
        fillPath(sd, sd.pixelFormat(), types, nt, coords, nc, path.getWindingRule(), sg2d.eargb, rule, cx1, cy1, cx2, cy2, spans);
    }

    /** Fills the device-space path into the surface's pixels: colour with its alpha, Porter-Duff rule, clip box and optional clip spans. */
    private static native void fillPath(SKRasterSurfaceData sd, int pixelFormat, byte[] types, int typeCount,
                                        float[] coords, int coordCount, int windingRule, int argb, int compositeRule,
                                        int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);
}
