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
import sun.java2d.StateTracker;
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
    /** The converters (stateless) with Marlin's antialiased parameters and the loops' non-antialiased ones. */
    private static final PixelToParallelogramConverter aaConverter = new PixelToParallelogramConverter(shapePipe, shapePipe, 1.0 / 8.0, 0.499, false);
    private static final PixelToParallelogramConverter converter = new PixelToParallelogramConverter(shapePipe, shapePipe, 1.0, 0.25, true);
    private static final SKRasterFillPipe fillPipe = new SKRasterFillPipe(converter);

    /** The raster surface for the image, or null when the image's type stays on the loops. */
    public static SurfaceData createData(BufferedImage img, double scaleX, double scaleY) {
        if (img.getRaster().getWidth() > 0xffff || img.getRaster().getHeight() > 0xffff) {
            return null;   // the natives pack device points into 16 bits a coordinate; the loops take the rest
        }
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
        sd.trackWrites();
        return sd;
    }

    /** The pixels' state tracker as of the last mark: still current means no Java-side write since. */
    private StateTracker javaWrites;
    /** A Java-side write was seen since the last raster operation. */
    private boolean javaWrote = true;

    /**
     * SunGraphics2D marks the surface dirty before every operation, through the same tracker a
     * Java-side write (setRGB, the raster's setters) invalidates. So the tracker is read here, before
     * the mark: stale means Java wrote the pixels since the last operation; then it is renewed. A
     * stolen array (an untrackable buffer) reads as written every time, which keeps the mirror off.
     */
    @Override
    public void markDirty() {
        if (javaWrites == null || !javaWrites.isCurrent()) {
            javaWrote = true;
        }
        super.markDirty();
        javaWrites = getStateTracker();
    }

    /**
     * Whether nothing wrote the pixels from Java since the last raster operation; with that and no
     * other write lock since (counted natively) the natives keep an INT_ARGB surface's
     * premultiplied mirror instead of converting the touched pixels in again. Once per operation.
     */
    boolean untouchedByJava() {
        boolean untouched = !javaWrote && javaWrites != null && javaWrites.isCurrent();
        javaWrote = false;
        return untouched;
    }

    /** Wraps the surface's native lock so every write lock, by any pipe on any thread, is counted. */
    private native void trackWrites();

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
        if (SKEnvironment.textBySkia() && sg2d.textpipe != outlineTextRenderer
                && sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR && sg2d.compositeState <= SunGraphics2D.COMP_ALPHA) {
            sg2d.textpipe = SKRasterTextPipe.of(sg2d.textpipe);   // Skia's text path for strikes on the Skia scaler, the loops' glyph images otherwise
        }
        if ((sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR || SKRasterShapePipe.isGradient(sg2d))
                && sg2d.compositeState <= SunGraphics2D.COMP_ALPHA) {
            if (sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON) {
                sg2d.drawpipe = aaConverter;
                sg2d.fillpipe = aaConverter;
                sg2d.shapepipe = aaConverter;
            } else {
                // the loops' non-antialiased converter rules (a 1-px pen, quarter-pixel normalization,
                // adjusted fills); thin strokes stay the loops' Bresenham lines
                if (sg2d.strokeState != SunGraphics2D.STROKE_THIN) {
                    sg2d.drawpipe = converter;
                }
                sg2d.fillpipe = fillPipe;
                sg2d.shapepipe = converter;
            }
        }
    }
}
