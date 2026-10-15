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
import sun.java2d.pipe.BufferedPaints;

import java.awt.MultipleGradientPaint;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;

/**
 * Which Paint types the Skia backend can render natively (as Skia shaders).
 * Everything else falls back to the software paint pipes.
 */
final class SKPaints {

    private SKPaints() {}

    static boolean isValid(SunGraphics2D sg2d) {
        switch (sg2d.paintState) {
            case SunGraphics2D.PAINT_GRADIENT:
                return true;
            case SunGraphics2D.PAINT_LIN_GRADIENT:
            case SunGraphics2D.PAINT_RAD_GRADIENT:
                return isMultiGradientValid((MultipleGradientPaint) sg2d.paint);
            case SunGraphics2D.PAINT_TEXTURE:
                return isTextureValid(sg2d);
            default:
                return false;
        }
    }

    private static boolean isMultiGradientValid(MultipleGradientPaint paint) {
        // BufferedPaints can only encode this many stops
        return paint.getFractions().length <= BufferedPaints.MULTI_MAX_FRACTIONS;
    }

    /**
     * A TexturePaint is accelerated once its image is cached as a Skia texture
     * (the surface-data proxy caches it after a few uses). Asking for the
     * source surface here, outside the queue lock, is what makes the proxy
     * cache it; doing so from the native paint setup would create the texture
     * while the queue lock is held and the destination is being validated.
     * Same rule as MTLPaints.
     */
    private static boolean isTextureValid(SunGraphics2D sg2d) {
        BufferedImage img = ((TexturePaint) sg2d.paint).getImage();
        SurfaceData dstData = sg2d.surfaceData;
        SurfaceData srcData = dstData.getSourceSurfaceData(img, SunGraphics2D.TRANSFORM_ISIDENT, CompositeType.SrcOver, null);
        if (!(srcData instanceof SKSurfaceData)) {
            // the first lookup counts a use; the second may already find the cached texture
            srcData = dstData.getSourceSurfaceData(img, SunGraphics2D.TRANSFORM_ISIDENT, CompositeType.SrcOver, null);
        }
        return srcData instanceof SKSurfaceData skData && skData.getType() == SKSurfaceData.TEXTURE;
    }
}
