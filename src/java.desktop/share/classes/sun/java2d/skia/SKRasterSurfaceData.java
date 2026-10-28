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

import java.awt.image.BufferedImage;
import sun.awt.image.IntegerComponentRaster;

import sun.awt.SunHints;
import sun.awt.image.BufImgSurfaceData;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.PixelToParallelogramConverter;

/**
 * A BufferedImage surface whose antialiased shapes Skia's raster backend
 * draws, directly into the image's pixels on the calling thread. It is a
 * {@link BufImgSurfaceData} in every other respect: the same surface type, so
 * every software loop, blit and read-back works unchanged, and the loops
 * remain the pipes for what Skia does not take yet. Selected by
 * {@code sun.java2d.skia.raster=true} for INT_ARGB, INT_ARGB_PRE and INT_RGB
 * images (see {@link SKEnvironment#rasterSurfaces()}).
 */
public final class SKRasterSurfaceData extends BufImgSurfaceData {

    /** Pixel formats the native side understands, in {@link SKSurfaceData}'s numbering. */
    private final int pixelFormat;
    private final int width, height;

    private static final SKRasterShapePipe shapePipe = new SKRasterShapePipe();
    private static final SKRasterDrawImage imagePipe = new SKRasterDrawImage();

    /** The raster surface for the image, or null when the image's type stays on the loops. */
    public static SurfaceData createData(BufferedImage img, double scaleX, double scaleY) {
        SurfaceType type; int pf;
        switch (img.getType()) {
            case BufferedImage.TYPE_INT_ARGB:     type = SurfaceType.IntArgb;    pf = SKSurfaceData.PF_INT_ARGB; break;
            case BufferedImage.TYPE_INT_ARGB_PRE: type = SurfaceType.IntArgbPre; pf = SKSurfaceData.PF_INT_ARGB_PRE; break;
            case BufferedImage.TYPE_INT_RGB:      type = SurfaceType.IntRgb;     pf = SKSurfaceData.PF_INT_RGB; break;
            default: return null;
        }
        IntegerComponentRaster raster = (IntegerComponentRaster) img.getRaster();
        int[] offsets = raster.getDataOffsets();
        if (offsets.length != 1 || raster.getPixelStride() != 1) {
            return null;   // not one int per pixel: the loops know these layouts, Skia's raster does not
        }
        SKRasterSurfaceData sd = new SKRasterSurfaceData(img, type, pf, scaleX, scaleY);
        sd.initRaster(raster.getDataStorage(), offsets[0] * 4, 0, raster.getWidth(), raster.getHeight(),
                      raster.getPixelStride() * 4, raster.getScanlineStride() * 4, null);
        sd.initSolidLoops();
        return sd;
    }

    private SKRasterSurfaceData(BufferedImage img, SurfaceType type, int pixelFormat, double scaleX, double scaleY) {
        super(img.getRaster().getDataBuffer(), img, type, scaleX, scaleY);
        this.pixelFormat = pixelFormat;
        this.width = img.getRaster().getWidth();
        this.height = img.getRaster().getHeight();
    }

    int pixelFormat() {
        return pixelFormat;
    }

    /** The image's size: the native side keeps a canvas over the whole image, not over one operation's box. */
    int imageWidth() { return width; }
    int imageHeight() { return height; }

    /**
     * The loops' pipes for everything, then Skia for the antialiased shapes a
     * solid colour or a gradient and a Porter-Duff composite can express: fills and strokes
     * of any shape, and the antialiased rectangles and lines the converter
     * turns into parallelograms.
     */
    @Override
    public void validatePipe(SunGraphics2D sg2d) {
        super.validatePipe(sg2d);
        sg2d.imagepipe = imagePipe;   // the loops' blits for copies, Skia for transformed images
        if (sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON
                && (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR || SKRasterShapePipe.isGradient(sg2d))
                && sg2d.compositeState <= SunGraphics2D.COMP_ALPHA) {
            PixelToParallelogramConverter converter =
                    new PixelToParallelogramConverter(shapePipe, shapePipe, 1.0 / 8.0, 0.499, false);
            sg2d.drawpipe = converter;
            sg2d.fillpipe = converter;
            sg2d.shapepipe = converter;
        }
    }
}
