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

import java.awt.Composite;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.BufferedTextPipe;
import sun.java2d.pipe.RenderQueue;

/**
 * Text pipe of the Skia backend. Java computes the glyph images and positions
 * ({@code sun.font}); Skia only composites them, from the backend's glyph cache
 * ({@code SKGlyphCache}). Mirrors {@code MTLTextRenderer}: the glyph lists are
 * render-queue operations ({@code DRAW_GLYPH_LIST}) dispatched by
 * {@code SKRenderQueue.cpp} to {@code SKTextPipe::drawGlyphList}.
 */
final class SKTextPipe extends BufferedTextPipe {

    SKTextPipe(RenderQueue renderQueue) {
        super(renderQueue);
    }

    @Override
    protected void drawGlyphList(int numGlyphs, boolean usePositions,
                                 boolean subPixPos, boolean rgbOrder,
                                 int lcdContrast,
                                 float glOrigX, float glOrigY,
                                 long[] images, float[] positions) {
        ((SKRenderQueue) rq).directEntryDone(drawGlyphListNative(numGlyphs, usePositions, subPixPos, rgbOrder,
                                                                 lcdContrast, glOrigX, glOrigY, images, positions));
    }

    /** The direct entry; whether its flush holds recordings (SKRenderQueue.directEntryDone). */
    private native boolean drawGlyphListNative(int numGlyphs, boolean usePositions,
                                               boolean subPixPos, boolean rgbOrder,
                                               int lcdContrast,
                                               float glOrigX, float glOrigY,
                                               long[] images, float[] positions);

    @Override
    protected void validateContext(SunGraphics2D sunGraphics2D, Composite composite) {
        SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
        SKContext.validateContext(destination, destination,
                sunGraphics2D.getCompClip(), composite,
                null, sunGraphics2D.paint, sunGraphics2D,
                SKContext.NO_CONTEXT_FLAGS);
    }
}
