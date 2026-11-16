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
import sun.java2d.loops.SurfaceType;
import sun.java2d.loops.TransformBlit;
import sun.java2d.pipe.DrawImage;

import java.awt.Color;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;

/** Image pipe of the Skia backend; mirrors {@code MTLDrawImage} without GPU BufferedImageOps. */
final class SKDrawImage extends DrawImage {

    @Override
    @SuppressWarnings("DuplicatedCode")   // the transform-blit lookup every accelerated image pipe spells out (MTL, D3D, OGL)
    protected void renderImageXform(SunGraphics2D sunGraphics2D, Image image, AffineTransform transform,
                                    int interpolationType, int sx1, int sy1, int sx2, int sy2, Color backgroundColor) {
        if (interpolationType != AffineTransformOp.TYPE_BICUBIC) {
            SurfaceData destination = sunGraphics2D.surfaceData;
            SurfaceData source = destination.getSourceSurfaceData(image, SunGraphics2D.TRANSFORM_GENERIC,
                                                                  sunGraphics2D.imageComp, backgroundColor);
            if (source != null && !isBgOperation(source, backgroundColor)) {
                SurfaceType sourceType = source.getSurfaceType();
                SurfaceType destinationType = destination.getSurfaceType();
                TransformBlit blit = TransformBlit.getFromCache(sourceType, sunGraphics2D.imageComp, destinationType);
                if (blit != null) {
                    blit.Transform(source, destination, sunGraphics2D.composite, sunGraphics2D.getCompClip(),
                                   transform, interpolationType, sx1, sy1, 0, 0, sx2 - sx1, sy2 - sy1);
                    return;
                }
            }
        }
        super.renderImageXform(sunGraphics2D, image, transform, interpolationType, sx1, sy1, sx2, sy2, backgroundColor);
    }

    @Override
    public void transformImage(SunGraphics2D sunGraphics2D, BufferedImage image, BufferedImageOp operation, int x, int y) {
        if (operation != null) {
            if (operation instanceof AffineTransformOp affineTransformOp) {
                transformImage(sunGraphics2D, image, x, y, affineTransformOp.getTransform(),
                               affineTransformOp.getInterpolationType());
                return;
            }
            // no GPU BufferedImageOps: filtered in software
            image = operation.filter(image, null);
        }
        copyImage(sunGraphics2D, image, x, y, null);
    }
}
