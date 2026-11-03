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

package sun.java2d.vulkan;

import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.loops.MaskFill;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.PixelDrawPipe;
import sun.java2d.pipe.PixelFillPipe;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.ShapeDrawPipe;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import static sun.java2d.vulkan.VKRichBufferedOpCodes.DRAW_AARRECT;
import static sun.java2d.vulkan.VKRichBufferedOpCodes.FILL_AARRECT;

public class VKRichRendererProxy
        implements PixelDrawPipe, PixelFillPipe, ShapeDrawPipe {

    public static boolean shouldBeInstalled(SunGraphics2D sg2d) {
        // Conditions:
        // * AA enabled.
        // * No logic XOR / general composites.
        // * No rotations / shears - rects stay axis-aligned.
        // * Composite+paint would be accepted by VKMaskFill.
        //   This is checked because VKMaskFill uses the same coverage shader machinery as the rich AA ops.
        return sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON &&
                sg2d.compositeState <= SunGraphics2D.COMP_ALPHA &&
                sg2d.transformState <= SunGraphics2D.TRANSFORM_TRANSLATESCALE &&
                MaskFill.getTraceWrapTarget(sg2d.alphafill) instanceof VKMaskFill;
    }

    public VKRichRendererProxy(RenderQueue rq, PixelDrawPipe pixelDrawFallbackDelegate, PixelFillPipe pixelFillFallbackDelegate, ShapeDrawPipe shapeDrawFallbackDelegate) {
        this.rq = rq;
        this.pixelDrawFallbackDelegate = pixelDrawFallbackDelegate;
        this.pixelFillFallbackDelegate = pixelFillFallbackDelegate;
        this.shapeDrawFallbackDelegate = shapeDrawFallbackDelegate;
    }

    private final RenderQueue rq;
    private final PixelDrawPipe pixelDrawFallbackDelegate;
    private final PixelFillPipe pixelFillFallbackDelegate;
    private final ShapeDrawPipe shapeDrawFallbackDelegate;

    private void validateContext(SunGraphics2D sg2d) {
        VKMaskFill maskFill = ((VKMaskFill) MaskFill.getTraceWrapTarget(sg2d.alphafill));
        maskFill.validateContext(sg2d, maskFill.getMaskFillComposite(sg2d.composite), BufferedContext.USE_MASK);
    }

    @Override
    public void drawLine(SunGraphics2D sg2d, int x1, int y1, int x2, int y2) {
        pixelDrawFallbackDelegate.drawLine(sg2d, x1, y1, x2, y2);
    }

    @Override
    public void drawRect(SunGraphics2D sg2d, int x, int y, int width, int height) {
        // This can become a proper rrect if using round joins
        if (tryDrawRoundRect(sg2d, x, y, width, height, 0.0, 0.0)) return;

        pixelDrawFallbackDelegate.drawRect(sg2d, x, y, width, height);
    }

    @Override
    public void drawRoundRect(SunGraphics2D sg2d, int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (tryDrawRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight)) return;

        pixelDrawFallbackDelegate.drawRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void drawOval(SunGraphics2D sg2d, int x, int y, int width, int height) {
        if (tryDrawRoundRect(sg2d, x, y, width, height, width, height)) return;

        pixelDrawFallbackDelegate.drawOval(sg2d, x, y, width, height);
    }

    @Override
    public void drawArc(SunGraphics2D sg2d, int x, int y, int width, int height, int startAngle, int arcAngle) {
        pixelDrawFallbackDelegate.drawArc(sg2d, x, y, width, height, startAngle, arcAngle);
    }

    @Override
    public void drawPolyline(SunGraphics2D sg2d, int[] xPoints, int[] yPoints, int nPoints) {
        pixelDrawFallbackDelegate.drawPolyline(sg2d, xPoints, yPoints, nPoints);
    }

    @Override
    public void drawPolygon(SunGraphics2D sg2d, int[] xPoints, int[] yPoints, int nPoints) {
        pixelDrawFallbackDelegate.drawPolygon(sg2d, xPoints, yPoints, nPoints);
    }

    @Override
    public void fillRect(SunGraphics2D sg2d, int x, int y, int width, int height) {
        // Let the parallelogram path handle this
        pixelFillFallbackDelegate.fillRect(sg2d, x, y, width, height);
    }

    @Override
    public void fillRoundRect(SunGraphics2D sg2d, int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (tryFillRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight)) return;

        pixelFillFallbackDelegate.fillRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void fillOval(SunGraphics2D sg2d, int x, int y, int width, int height) {
        if (tryFillRoundRect(sg2d, x, y, width, height, width, height)) return;

        pixelFillFallbackDelegate.fillOval(sg2d, x, y, width, height);
    }

    @Override
    public void fillArc(SunGraphics2D sg2d, int x, int y, int width, int height, int startAngle, int arcAngle) {
        pixelFillFallbackDelegate.fillArc(sg2d, x, y, width, height, startAngle, arcAngle);
    }

    @Override
    public void fillPolygon(SunGraphics2D sg2d, int[] xPoints, int[] yPoints, int nPoints) {
        pixelFillFallbackDelegate.fillPolygon(sg2d, xPoints, yPoints, nPoints);
    }

    @Override
    public void draw(SunGraphics2D sg2d, Shape s) {
        if (s instanceof Rectangle2D rect) {
            // This can become a proper rrect if using round joins
            if (tryDrawRoundRect(sg2d,
                    rect.getX(), rect.getY(),
                    rect.getWidth(), rect.getHeight(),
                    0.0, 0.0)) return;
        } else if (s instanceof RoundRectangle2D roundRect) {
            if (tryDrawRoundRect(sg2d,
                    roundRect.getX(), roundRect.getY(),
                    roundRect.getWidth(), roundRect.getHeight(),
                    roundRect.getArcWidth(), roundRect.getArcHeight())) return;
        } else if (s instanceof Ellipse2D ellipse) {
            if (tryDrawRoundRect(sg2d,
                    ellipse.getX(), ellipse.getY(),
                    ellipse.getWidth(), ellipse.getHeight(),
                    ellipse.getWidth(), ellipse.getHeight())) return;
        }

        shapeDrawFallbackDelegate.draw(sg2d, s);
    }

    @Override
    public void fill(SunGraphics2D sg2d, Shape s) {
        if (s instanceof RoundRectangle2D roundRect) {
            if (tryFillRoundRect(sg2d,
                    roundRect.getX(), roundRect.getY(),
                    roundRect.getWidth(), roundRect.getHeight(),
                    roundRect.getArcWidth(), roundRect.getArcHeight())) return;
        } else if (s instanceof Ellipse2D ellipse) {
            if (tryFillRoundRect(sg2d,
                    ellipse.getX(), ellipse.getY(),
                    ellipse.getWidth(), ellipse.getHeight(),
                    ellipse.getWidth(), ellipse.getHeight())) return;
        }

        shapeDrawFallbackDelegate.fill(sg2d, s);
    }

    private boolean tryFillRoundRect(SunGraphics2D sg2d, double x, double y, double width, double height, double arcWidth, double arcHeight) {
        if (!(width > 0 && height > 0)) return true; // degenerate rrects fill nothing

        arcWidth = Math.min(Math.abs(arcWidth), width);
        arcHeight = Math.min(Math.abs(arcHeight), height);
        // The fast path can only draw circular corners.
        if (arcWidth != arcHeight) return false;

        // TRANSFORM_TRANSLATESCALE guarantees positive scale factors (no flips)
        double scale = sg2d.transform.getScaleX();
        // Only uniform scale is supported, otherwise the corners would be non-circular.
        // We have to re-check this every draw because shouldBeInstalled is only called on transformState changes.
        if (scale != sg2d.transform.getScaleY()) return false;
        double translateX = sg2d.transform.getTranslateX();
        double translateY = sg2d.transform.getTranslateY();

        // Compute the coords in device space
        double xMinDevice = scale * x + translateX;
        double yMinDevice = scale * y + translateY;
        double xMaxDevice = scale * (x + width) + translateX;
        double yMaxDevice = scale * (y + height) + translateY;
        double cornerDiameterDevice = scale * arcWidth;

        rq.lock();
        try {
            validateContext(sg2d);
            rq.ensureCapacity(24);
            RenderBuffer buf = rq.getBuffer();
            buf.putInt(FILL_AARRECT);
            buf.putFloat((float) xMinDevice);
            buf.putFloat((float) yMinDevice);
            buf.putFloat((float) xMaxDevice);
            buf.putFloat((float) yMaxDevice);
            buf.putFloat((float) cornerDiameterDevice);
        } finally {
            rq.unlock();
        }

        return true;
    }

    private boolean tryDrawRoundRect(SunGraphics2D sg2d, double x, double y, double width, double height, double arcWidth, double arcHeight) {
        if (!(width > 0 && height > 0)) return false; // degenerate rrects can still produce pixels when drawn

        if (sg2d.strokeState == SunGraphics2D.STROKE_CUSTOM) return false;
        BasicStroke stroke = (BasicStroke) sg2d.stroke;
        if (stroke.getDashArray() != null) return false;

        arcWidth = Math.min(Math.abs(arcWidth), width);
        arcHeight = Math.min(Math.abs(arcHeight), height);
        // The fast path can only draw circular corners.
        if (arcWidth != arcHeight) return false;
        // JOIN_ROUND will turn 'sharp' rectangles into proper rrects.
        if (arcWidth == 0 && stroke.getLineJoin() != BasicStroke.JOIN_ROUND) return false;

        // TRANSFORM_TRANSLATESCALE guarantees positive scale factors (no flips)
        double scale = sg2d.transform.getScaleX();
        // Only uniform scale is supported, otherwise the corners would be non-circular.
        // We have to re-check this every draw because shouldBeInstalled is only called on transformState changes.
        if (scale != sg2d.transform.getScaleY()) return false;
        double translateX = sg2d.transform.getTranslateX();
        double translateY = sg2d.transform.getTranslateY();

        double lwDevice = Math.max(scale * stroke.getLineWidth(), SunGraphics2D.MinPenSizeAA);

        // Compute the coords in device space
        double xMinDevice = scale * x + translateX;
        double yMinDevice = scale * y + translateY;
        double xMaxDevice = scale * (x + width) + translateX;
        double yMaxDevice = scale * (y + height) + translateY;
        double cornerDiameterDevice = scale * arcWidth;
        if (sg2d.strokeHint != SunHints.INTVAL_STROKE_PURE) {
            xMinDevice = normalize(xMinDevice);
            yMinDevice = normalize(yMinDevice);
            xMaxDevice = normalize(xMaxDevice);
            yMaxDevice = normalize(yMaxDevice);
            // Check if the rectangle has become degenerate after normalization
            if (xMinDevice >= xMaxDevice || yMinDevice >= yMaxDevice) return false;
            // Might have to re-clamp the corner diameter
            cornerDiameterDevice = Math.min(cornerDiameterDevice, Math.min(xMaxDevice - xMinDevice, yMaxDevice - yMinDevice));
        }

        double halfLwDevice = lwDevice / 2;
        rq.lock();
        try {
            validateContext(sg2d);
            rq.ensureCapacity(32);
            RenderBuffer buf = rq.getBuffer();
            buf.putInt(DRAW_AARRECT);
            buf.putFloat((float) (xMinDevice - halfLwDevice));
            buf.putFloat((float) (yMinDevice - halfLwDevice));
            buf.putFloat((float) (xMaxDevice + halfLwDevice));
            buf.putFloat((float) (yMaxDevice + halfLwDevice));
            buf.putFloat((float) (cornerDiameterDevice + lwDevice));
            buf.putFloat((float) lwDevice);
            buf.putFloat((float) Math.max(cornerDiameterDevice - lwDevice, 0.0));
        } finally {
            rq.unlock();
        }
        return true;
    }

    private double normalize(double v) {
        return Math.floor(v) + 0.5; // match Marlin's normalization
    }
}
