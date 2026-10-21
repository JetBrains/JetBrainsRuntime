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

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

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
        pixelDrawFallbackDelegate.drawRect(sg2d, x, y, width, height);
    }

    @Override
    public void drawRoundRect(SunGraphics2D sg2d, int x, int y, int width, int height, int arcWidth, int arcHeight) {
        pixelDrawFallbackDelegate.drawRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void drawOval(SunGraphics2D sg2d, int x, int y, int width, int height) {
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
        if (tryHandleRoundRect(sg2d, x, y, width, height, 0.0f, 0.0f)) return;

        pixelFillFallbackDelegate.fillRect(sg2d, x, y, width, height);
    }

    @Override
    public void fillRoundRect(SunGraphics2D sg2d, int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (tryHandleRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight)) return;

        pixelFillFallbackDelegate.fillRoundRect(sg2d, x, y, width, height, arcWidth, arcHeight);

    }

    @Override
    public void fillOval(SunGraphics2D sg2d, int x, int y, int width, int height) {
        if (tryHandleRoundRect(sg2d, x, y, width, height, width, height)) return;

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
        shapeDrawFallbackDelegate.draw(sg2d, s);
    }

    @Override
    public void fill(SunGraphics2D sg2d, Shape s) {
        if (s instanceof RoundRectangle2D roundRect && tryHandleRoundRect(sg2d,
                roundRect.getX(), roundRect.getY(),
                roundRect.getWidth(), roundRect.getHeight(),
                roundRect.getArcWidth(), roundRect.getArcHeight())) return;

        if (s instanceof Ellipse2D ellipse && tryHandleRoundRect(sg2d,
                ellipse.getX(), ellipse.getY(),
                ellipse.getWidth(), ellipse.getHeight(),
                ellipse.getWidth(), ellipse.getHeight())) return;

        shapeDrawFallbackDelegate.fill(sg2d, s);
    }

    private boolean tryHandleRoundRect(SunGraphics2D sg2d, double x, double y, double width, double height, double arcWidth, double arcHeight) {
        if (width <= 0 || height <= 0) return true;

        arcWidth = Math.min(Math.abs(arcWidth), width);
        arcHeight = Math.min(Math.abs(arcHeight), height);
        if (arcWidth != arcHeight) return false;

        // TRANSFORM_TRANSLATESCALE guarantee positive scale factors (no flips)
        double scaleX = sg2d.transform.getScaleX();
        double scaleY = sg2d.transform.getScaleY();
        if (arcWidth != 0 && scaleX != scaleY) return false;

        double translateX = sg2d.transform.getTranslateX();
        double translateY = sg2d.transform.getTranslateY();

        x = scaleX * x + translateX;
        y = scaleY * y + translateY;
        width *= scaleX;
        height *= scaleY;
        arcWidth *= scaleX;

        rq.lock();
        try {
            validateContext(sg2d);
            rq.ensureCapacity(24);
            RenderBuffer buf = rq.getBuffer();
            buf.putInt(FILL_AARRECT);
            buf.putFloat((float) x);
            buf.putFloat((float) y);
            buf.putFloat((float) width);
            buf.putFloat((float) height);
            buf.putFloat((float) arcWidth);
        } finally {
            rq.unlock();
        }

        return true;
    }
}
