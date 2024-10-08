/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.pipe.BufferedRenderPipe;
import sun.java2d.pipe.ParallelogramPipe;
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.SpanIterator;

import java.awt.Transparency;
import java.awt.geom.Path2D;
import sun.util.logging.PlatformLogger;

import static sun.java2d.pipe.BufferedOpCodes.COPY_AREA;

class VKRenderer extends BufferedRenderPipe {

    private static final PlatformLogger log =
            PlatformLogger.getLogger("sun.java2d.vulkan.VKRenderer");

    VKRenderer(RenderQueue rq) {
        super(rq);
    }

    @Override
    protected void validateContext(SunGraphics2D sg2d) {
        int ctxflags =
                sg2d.paint.getTransparency() == Transparency.OPAQUE ?
                        VKContext.SRC_IS_OPAQUE : VKContext.NO_CONTEXT_FLAGS;
        VKSurfaceData dstData = SurfaceData.convertTo(VKSurfaceData.class,
                                                       sg2d.surfaceData);
        VKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), sg2d.composite,
                null, sg2d.paint, sg2d, ctxflags);
    }

    @Override
    protected void validateContextAA(SunGraphics2D sg2d) {
        int ctxflags = VKContext.NO_CONTEXT_FLAGS;
        VKSurfaceData dstData = SurfaceData.convertTo(VKSurfaceData.class,
                                                       sg2d.surfaceData);
        VKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), sg2d.composite,
                null, sg2d.paint, sg2d, ctxflags);
    }

    void copyArea(SunGraphics2D sg2d,
                  int x, int y, int w, int h, int dx, int dy)
    {
        rq.lock();
        try {
            int ctxflags =
                    sg2d.surfaceData.getTransparency() == Transparency.OPAQUE ?
                            VKContext.SRC_IS_OPAQUE : VKContext.NO_CONTEXT_FLAGS;
            VKSurfaceData dstData = SurfaceData.convertTo(VKSurfaceData.class,
                                                           sg2d.surfaceData);
            VKContext.validateContext(dstData, dstData,
                    sg2d.getCompClip(), sg2d.composite,
                    null, null, null, ctxflags);

            rq.ensureCapacity(28);
            buf.putInt(COPY_AREA);
            buf.putInt(x).putInt(y).putInt(w).putInt(h);
            buf.putInt(dx).putInt(dy);
        } finally {
            rq.unlock();
        }
    }

    @Override
    protected void drawPoly(int[] xPoints, int[] yPoints,
                                   int nPoints, boolean isClosed,
                                   int transX, int transY) {
        log.info("Not implemented: VKRenderer.drawPoly(int[] xPoints, int[] yPoints,\n" +
                 "                                     int nPoints, boolean isClosed,\n" +
                 "                                     int transX, int transY)");
    }

    VKRenderer traceWrap() {
        return new Tracer(this);
    }

    private static class Tracer extends VKRenderer {
        private VKRenderer vkr;
        Tracer(VKRenderer vkr) {
            super(vkr.rq);
            this.vkr = vkr;
        }
        public ParallelogramPipe getAAParallelogramPipe() {
            final ParallelogramPipe realpipe = vkr.getAAParallelogramPipe();
            return new ParallelogramPipe() {
                public void fillParallelogram(SunGraphics2D sg2d,
                                              double ux1, double uy1,
                                              double ux2, double uy2,
                                              double x, double y,
                                              double dx1, double dy1,
                                              double dx2, double dy2)
                {
                    GraphicsPrimitive.tracePrimitive("VKFillAAParallelogram");
                    realpipe.fillParallelogram(sg2d,
                            ux1, uy1, ux2, uy2,
                            x, y, dx1, dy1, dx2, dy2);
                }
                public void drawParallelogram(SunGraphics2D sg2d,
                                              double ux1, double uy1,
                                              double ux2, double uy2,
                                              double x, double y,
                                              double dx1, double dy1,
                                              double dx2, double dy2,
                                              double lw1, double lw2)
                {
                    GraphicsPrimitive.tracePrimitive("VKDrawAAParallelogram");
                    realpipe.drawParallelogram(sg2d,
                            ux1, uy1, ux2, uy2,
                            x, y, dx1, dy1, dx2, dy2,
                            lw1, lw2);
                }
            };
        }
        protected void validateContext(SunGraphics2D sg2d) {
            vkr.validateContext(sg2d);
        }
        public void drawLine(SunGraphics2D sg2d,
                             int x1, int y1, int x2, int y2)
        {
            GraphicsPrimitive.tracePrimitive("VKDrawLine");
            vkr.drawLine(sg2d, x1, y1, x2, y2);
        }
        public void drawRect(SunGraphics2D sg2d, int x, int y, int w, int h) {
            GraphicsPrimitive.tracePrimitive("VKDrawRect");
            vkr.drawRect(sg2d, x, y, w, h);
        }
        protected void drawPoly(SunGraphics2D sg2d,
                                int[] xPoints, int[] yPoints,
                                int nPoints, boolean isClosed)
        {
            GraphicsPrimitive.tracePrimitive("VKDrawPoly");
            vkr.drawPoly(sg2d, xPoints, yPoints, nPoints, isClosed);
        }
        public void fillRect(SunGraphics2D sg2d, int x, int y, int w, int h) {
            GraphicsPrimitive.tracePrimitive("VKFillRect");
            vkr.fillRect(sg2d, x, y, w, h);
        }
        protected void drawPath(SunGraphics2D sg2d,
                                Path2D.Float p2df, int transx, int transy)
        {
            GraphicsPrimitive.tracePrimitive("VKDrawPath");
            vkr.drawPath(sg2d, p2df, transx, transy);
        }
        protected void fillPath(SunGraphics2D sg2d,
                                Path2D.Float p2df, int transx, int transy)
        {
            GraphicsPrimitive.tracePrimitive("VKFillPath");
            vkr.fillPath(sg2d, p2df, transx, transy);
        }
        protected void fillSpans(SunGraphics2D sg2d, SpanIterator si,
                                 int transx, int transy)
        {
            GraphicsPrimitive.tracePrimitive("VKFillSpans");
            vkr.fillSpans(sg2d, si, transx, transy);
        }
        public void fillParallelogram(SunGraphics2D sg2d,
                                      double ux1, double uy1,
                                      double ux2, double uy2,
                                      double x, double y,
                                      double dx1, double dy1,
                                      double dx2, double dy2)
        {
            GraphicsPrimitive.tracePrimitive("VKFillParallelogram");
            vkr.fillParallelogram(sg2d,
                    ux1, uy1, ux2, uy2,
                    x, y, dx1, dy1, dx2, dy2);
        }
        public void drawParallelogram(SunGraphics2D sg2d,
                                      double ux1, double uy1,
                                      double ux2, double uy2,
                                      double x, double y,
                                      double dx1, double dy1,
                                      double dx2, double dy2,
                                      double lw1, double lw2)
        {
            GraphicsPrimitive.tracePrimitive("VKDrawParallelogram");
            vkr.drawParallelogram(sg2d,
                    ux1, uy1, ux2, uy2,
                    x, y, dx1, dy1, dx2, dy2, lw1, lw2);
        }
        public void copyArea(SunGraphics2D sg2d,
                             int x, int y, int w, int h, int dx, int dy)
        {
            GraphicsPrimitive.tracePrimitive("VKCopyArea");
            vkr.copyArea(sg2d, x, y, w, h, dx, dy);
        }
    }
}
