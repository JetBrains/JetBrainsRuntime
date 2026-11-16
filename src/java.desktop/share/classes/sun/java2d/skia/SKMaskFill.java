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
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.GraphicsPrimitiveMgr;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.BufferedMaskFill;

import java.awt.Composite;

import static sun.java2d.loops.CompositeType.SrcNoEa;
import static sun.java2d.loops.CompositeType.SrcOver;
import static sun.java2d.loops.SurfaceType.*;

/**
 * Marlin-preserving AA path: coverage tiles computed on the CPU are composited
 * by Skia as A8 images. Mirrors {@code MTLMaskFill}.
 */
final class SKMaskFill extends BufferedMaskFill {

    static void register() {
        GraphicsPrimitive[] primitives = {
                new SKMaskFill(AnyColor,                  SrcOver),
                new SKMaskFill(OpaqueColor,               SrcNoEa),
                new SKMaskFill(GradientPaint,             SrcOver),
                new SKMaskFill(OpaqueGradientPaint,       SrcNoEa),
                new SKMaskFill(LinearGradientPaint,       SrcOver),
                new SKMaskFill(OpaqueLinearGradientPaint, SrcNoEa),
                new SKMaskFill(RadialGradientPaint,       SrcOver),
                new SKMaskFill(OpaqueRadialGradientPaint, SrcNoEa),
                new SKMaskFill(TexturePaint,              SrcOver),
                new SKMaskFill(OpaqueTexturePaint,        SrcNoEa),
        };
        GraphicsPrimitiveMgr.register(primitives);
    }

    private SKMaskFill(SurfaceType sourceType, CompositeType compositeType) {
        super(SKRenderQueue.getInstance(), sourceType, compositeType, SKSurfaceData.SKSurface);
    }

    @Override
    protected void maskFill(int x, int y, int width, int height,
                            int maskOffset, int maskScan, int maskLength,
                            byte[] mask) {
        ((SKRenderQueue) rq).directEntryDone(maskFillNative(x, y, width, height, maskOffset, maskScan, maskLength, mask));
    }

    /** The direct entry; whether its flush holds recordings (SKRenderQueue.directEntryDone). */
    private native boolean maskFillNative(int x, int y, int width, int height,
                                          int maskOffset, int maskScan, int maskLength,
                                          byte[] mask);

    @Override
    protected void validateContext(SunGraphics2D sunGraphics2D, Composite composite, int contextFlags) {
        SKSurfaceData destination = SurfaceData.convertTo(SKSurfaceData.class, sunGraphics2D.surfaceData);
        SKContext.validateContext(destination, destination,
                sunGraphics2D.getCompClip(), composite,
                null, sunGraphics2D.paint, sunGraphics2D, contextFlags);
    }
}
