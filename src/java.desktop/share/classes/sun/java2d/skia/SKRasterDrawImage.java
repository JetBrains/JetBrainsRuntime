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
import java.awt.Color;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

import sun.awt.image.BufImgSurfaceData;
import sun.awt.image.IntegerComponentRaster;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.DrawImage;
import sun.java2d.pipe.Region;

/**
 * The image pipe of a {@link SKRasterSurfaceData}: the loops' {@link DrawImage}
 * for copies and integer translations (blits, already copies), Skia for every
 * transformed draw of a BufferedImage in the IDE's pixel formats — scaled,
 * rotated, sheared, with nearest-neighbour, bilinear or bicubic sampling. The
 * source's pixels are read in place, locked like the destination's (an
 * unpremultiplied source through a premultiplied copy of the rectangle); the
 * destination pixels drawn and the samples a filter reads at the source's edge
 * follow Java's transform helper (centre inside the source; neighbours clamped).
 * Other sources, background-colour draws and custom composites stay with the
 * loops.
 */
final class SKRasterDrawImage extends DrawImage {

    /** Transformed image draws Skia did, for the test of the route. */
    static final AtomicLong imagesBySkia = new AtomicLong();

    @Override
    protected void renderImageXform(SunGraphics2D sg, Image img, AffineTransform tx, int interpType,
                                    int sx1, int sy1, int sx2, int sy2, Color bgColor) {
        if (bgColor == null && sg.compositeState <= SunGraphics2D.COMP_ALPHA && img instanceof BufferedImage bi
                && sg.surfaceData instanceof SKRasterSurfaceData dst && transformBySkia(sg, bi, dst, tx, interpType, sx1, sy1, sx2, sy2)) {
            return;
        }
        super.renderImageXform(sg, img, tx, interpType, sx1, sy1, sx2, sy2, bgColor);
    }

    private static boolean transformBySkia(SunGraphics2D sg, BufferedImage src, SKRasterSurfaceData dst, AffineTransform tx, int interpType,
                                           int sx1, int sy1, int sx2, int sy2) {
        int srcPf;
        switch (src.getType()) {
            case BufferedImage.TYPE_INT_ARGB -> srcPf = SKSurfaceData.PF_INT_ARGB;
            case BufferedImage.TYPE_INT_ARGB_PRE -> srcPf = SKSurfaceData.PF_INT_ARGB_PRE;
            case BufferedImage.TYPE_INT_RGB -> srcPf = SKSurfaceData.PF_INT_RGB;
            default -> { return false; }
        }
        if (!(src.getRaster() instanceof IntegerComponentRaster icr) || icr.getPixelStride() != 1 || icr.getDataOffsets().length != 1) {
            return false;
        }
        SurfaceData srcData = SurfaceData.getPrimarySurfaceData(src);
        if (!(srcData instanceof BufImgSurfaceData) || srcData == dst) {
            return false;   // the loops know how to lock everything else; drawing an image onto itself stays theirs too
        }
        // the destination box: the transformed source corners, as DrawImage computes them
        double w = sx2 - sx1, h = sy2 - sy1;
        double[] c = {0, 0, w, 0, 0, h, w, h};
        tx.transform(c, 0, c, 0, 4);
        double ddx1 = c[0], ddy1 = c[1], ddx2 = c[0], ddy2 = c[1];
        for (int i = 2; i < 8; i += 2) { ddx1 = Math.min(ddx1, c[i]); ddx2 = Math.max(ddx2, c[i]); ddy1 = Math.min(ddy1, c[i + 1]); ddy2 = Math.max(ddy2, c[i + 1]); }
        Region clip = sg.getCompClip();
        int dx1 = Math.max((int) Math.floor(ddx1), clip.getLoX()), dy1 = Math.max((int) Math.floor(ddy1), clip.getLoY());
        int dx2 = Math.min((int) Math.ceil(ddx2), clip.getHiX()), dy2 = Math.min((int) Math.ceil(ddy2), clip.getHiY());
        if (dx2 <= dx1 || dy2 <= dy1) {
            return true;   // nothing visible
        }
        float extraAlpha = sg.composite instanceof AlphaComposite ac ? ac.getAlpha() : 1f;
        int rule = sg.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
        float[] m = {(float) tx.getScaleX(), (float) tx.getShearY(), (float) tx.getShearX(), (float) tx.getScaleY(), (float) tx.getTranslateX(), (float) tx.getTranslateY()};
        imagesBySkia.incrementAndGet();
        drawImage(dst, dst.pixelFormat(), dst.imageWidth(), dst.imageHeight(), srcData, srcPf, icr.getWidth(), icr.getHeight(),
                  sx1, sy1, sx2, sy2, m, interpType, extraAlpha, rule, dx1, dy1, dx2, dy2, SKRasterShapePipe.spansOf(clip, dx1, dy1, dx2, dy2));
        return true;
    }

    /**
     * Draws the source rectangle under the transform (which maps the rectangle's origin to the
     * destination), sampling with the interpolation type of AffineTransformOp (1 nearest, 2 bilinear,
     * 3 bicubic), outside samples transparent; the source is locked for reading while it is drawn.
     */
    private static native void drawImage(SKRasterSurfaceData dst, int dstPixelFormat, int dstWidth, int dstHeight,
                                         SurfaceData src, int srcPixelFormat, int srcWidth, int srcHeight, int sx1, int sy1, int sx2, int sy2,
                                         float[] xform, int interpType, float extraAlpha, int compositeRule,
                                         int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);
}
