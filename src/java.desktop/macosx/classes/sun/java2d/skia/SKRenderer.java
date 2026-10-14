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

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.BufferedRenderPipe;
import sun.java2d.pipe.RenderQueue;

import java.awt.Transparency;

import static sun.java2d.pipe.BufferedOpCodes.COPY_AREA;

/** Shape/line/rect pipe of the Skia backend; mirrors {@code MTLRenderer}. */
final class SKRenderer extends BufferedRenderPipe {

    SKRenderer(RenderQueue rq) {
        super(rq);
    }

    @Override
    protected void validateContext(SunGraphics2D sg2d) {
        int ctxflags = sg2d.paint.getTransparency() == Transparency.OPAQUE ?
                SKContext.SRC_IS_OPAQUE : SKContext.NO_CONTEXT_FLAGS;
        SKSurfaceData dstData = SurfaceData.convertTo(SKSurfaceData.class, sg2d.surfaceData);
        SKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), sg2d.composite,
                null, sg2d.paint, sg2d, ctxflags);
    }

    @Override
    protected void validateContextAA(SunGraphics2D sg2d) {
        SKSurfaceData dstData = SurfaceData.convertTo(SKSurfaceData.class, sg2d.surfaceData);
        SKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), sg2d.composite,
                null, sg2d.paint, sg2d, SKContext.NO_CONTEXT_FLAGS);
    }

    void copyArea(SunGraphics2D sg2d, int x, int y, int w, int h, int dx, int dy) {
        rq.lock();
        try {
            int ctxflags = sg2d.surfaceData.getTransparency() == Transparency.OPAQUE ?
                    SKContext.SRC_IS_OPAQUE : SKContext.NO_CONTEXT_FLAGS;
            SKSurfaceData dstData = SurfaceData.convertTo(SKSurfaceData.class, sg2d.surfaceData);
            SKContext.validateContext(dstData, dstData,
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
    protected native void drawPoly(int[] xPoints, int[] yPoints,
                                   int nPoints, boolean isClosed,
                                   int transX, int transY);
}
