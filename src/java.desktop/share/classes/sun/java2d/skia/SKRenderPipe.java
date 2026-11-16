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
final class SKRenderPipe extends BufferedRenderPipe {

    /** The {@code COPY_AREA} entry: the opcode, x, y, width, height, dx, dy. */
    private static final int COPY_AREA_ENTRY_BYTES = 7 * Integer.BYTES;

    SKRenderPipe(RenderQueue renderQueue) {
        super(renderQueue);
    }

    @Override
    protected void validateContext(SunGraphics2D sunGraphics2D) {
        int contextFlags = sunGraphics2D.paint.getTransparency() == Transparency.OPAQUE ?
                SKContext.SRC_IS_OPAQUE : SKContext.NO_CONTEXT_FLAGS;
        SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
        SKContext.validateContext(destination, destination,
                sunGraphics2D.getCompClip(), sunGraphics2D.composite,
                null, sunGraphics2D.paint, sunGraphics2D, contextFlags);
    }

    @Override
    protected void validateContextAA(SunGraphics2D sunGraphics2D) {
        SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
        SKContext.validateContext(destination, destination,
                sunGraphics2D.getCompClip(), sunGraphics2D.composite,
                null, sunGraphics2D.paint, sunGraphics2D, SKContext.NO_CONTEXT_FLAGS);
    }

    @SuppressWarnings("SuspiciousNameCombination")   // RenderBuffer.putInt names its one parameter x
    void copyArea(SunGraphics2D sunGraphics2D, int x, int y, int width, int height, int dx, int dy) {
        rq.lock();
        try {
            int contextFlags = sunGraphics2D.surfaceData.getTransparency() == Transparency.OPAQUE ?
                    SKContext.SRC_IS_OPAQUE : SKContext.NO_CONTEXT_FLAGS;
            SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
            SKContext.validateContext(destination, destination,
                    sunGraphics2D.getCompClip(), sunGraphics2D.composite,
                    null, null, null, contextFlags);
            rq.ensureCapacity(COPY_AREA_ENTRY_BYTES);
            buf.putInt(COPY_AREA);
            buf.putInt(x).putInt(y).putInt(width).putInt(height);
            buf.putInt(dx).putInt(dy);
        } finally {
            rq.unlock();
        }
    }

    @Override
    protected void drawPoly(int[] xPoints, int[] yPoints,
                            int nPoints, boolean isClosed,
                            int transX, int transY) {
        ((SKRenderQueue) rq).directEntryDone(drawPolyNative(xPoints, yPoints, nPoints, isClosed, transX, transY));
    }

    /** The direct entry; whether its flush holds recordings (SKRenderQueue.directEntryDone). */
    private native boolean drawPolyNative(int[] xPoints, int[] yPoints,
                                          int nPoints, boolean isClosed,
                                          int transX, int transY);
}
