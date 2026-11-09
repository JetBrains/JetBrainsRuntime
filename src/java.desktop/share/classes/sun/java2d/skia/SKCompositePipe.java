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

import sun.awt.SunHints;
import sun.awt.image.BufImgSurfaceData;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.Blit;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.MaskBlit;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.AAShapePipe;
import sun.java2d.pipe.CompositePipe;
import sun.java2d.pipe.PixelToParallelogramConverter;
import sun.java2d.pipe.PixelToShapeConverter;
import sun.java2d.pipe.Region;
import sun.java2d.pipe.SpanClipRenderer;
import sun.java2d.pipe.SpanShapeRenderer;
import sun.java2d.pipe.TextRenderer;

import java.awt.AlphaComposite;
import java.awt.CompositeContext;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * The composites Skia has no blender for: a {@code Composite} of the application's
 * ({@code COMP_CUSTOM}) and XOR with a paint that is not a color. The software pipes
 * for them ({@code GeneralCompositePipe}, {@code AlphaPaintPipe}) work on the
 * destination's pixels, which a Skia surface does not lend out ({@code getRaster},
 * {@code Lock}): this pipe reads the destination region of a shape, glyph list or
 * span run back into a buffer in the surface's color model, composes the tiles into
 * the buffer as those pipes compose them into a software surface, and writes the
 * buffer back, so that the result is the software loops'. Correct, not fast: a
 * read-back and a write-back per shape. Images under these composites go through
 * {@link SKAnyCompositeBlit}, the same way round.
 */
final class SKCompositePipe implements CompositePipe {

    private static final SKCompositePipe customPipe = new SKCompositePipe(false);
    private static final SKCompositePipe xorPaintPipe = new SKCompositePipe(true);

    // SurfaceData's pipes for COMP_CUSTOM (compShape, compViaShape, compText, clipCompText,
    // AACompViaShape, AAClipCompViaShape), over this pipe
    private static final SpanShapeRenderer customShape = new SpanShapeRenderer.Composite(customPipe);
    private static final PixelToShapeConverter customViaShape = new PixelToShapeConverter(customShape);
    private static final TextRenderer customText = new TextRenderer(customPipe);
    private static final CompositePipe clippedCustomPipe = new SpanClipRenderer(customPipe);
    private static final TextRenderer clippedCustomText = new TextRenderer(clippedCustomPipe);
    private static final PixelToParallelogramConverter aaCustomViaShape = aaConverter(new AAShapePipe(customPipe));
    private static final PixelToParallelogramConverter aaClippedCustomViaShape = aaConverter(new AAShapePipe(clippedCustomPipe));
    // and for XOR with a paint
    private static final SpanShapeRenderer xorPaintShape = new SpanShapeRenderer.Composite(xorPaintPipe);
    private static final PixelToShapeConverter xorPaintViaShape = new PixelToShapeConverter(xorPaintShape);

    /** The surface to an opaque buffer: the stored bytes, as they are presented (no unpremultiplication). */
    private static final Blit surfaceToIntRgb = SKBlitLoops.surfaceToIntRgb();

    private static PixelToParallelogramConverter aaConverter(AAShapePipe pipe) {
        return new PixelToParallelogramConverter(pipe, pipe,
                SKSurfaceData.AA_MIN_PEN_SIZE, SKSurfaceData.AA_NORMALIZE_POSITION, /* adjustfill */ false);
    }

    /** Whether this pipe draws the state: a custom Composite, or XOR with a paint. */
    static boolean handles(SunGraphics2D sunGraphics2D) {
        return sunGraphics2D.compositeState == SunGraphics2D.COMP_CUSTOM
                || (sunGraphics2D.compositeState == SunGraphics2D.COMP_XOR
                    && sunGraphics2D.paintState > SunGraphics2D.PAINT_ALPHACOLOR);
    }

    /** Installs the pipes {@code SurfaceData.validatePipe} installs for the state, over this pipe. */
    static void validatePipe(SunGraphics2D sunGraphics2D) {
        boolean clippedByShape = sunGraphics2D.clipState == SunGraphics2D.CLIP_SHAPE;
        if (sunGraphics2D.compositeState == SunGraphics2D.COMP_XOR) {
            sunGraphics2D.drawpipe = xorPaintViaShape;
            sunGraphics2D.fillpipe = xorPaintViaShape;
            sunGraphics2D.shapepipe = xorPaintShape;
            sunGraphics2D.textpipe = SurfaceData.outlineTextRenderer;
        } else if (sunGraphics2D.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON) {
            PixelToParallelogramConverter converter = clippedByShape ? aaClippedCustomViaShape : aaCustomViaShape;
            sunGraphics2D.drawpipe = converter;
            sunGraphics2D.fillpipe = converter;
            sunGraphics2D.shapepipe = converter;
            sunGraphics2D.textpipe = clippedByShape ? clippedCustomText : customText;
        } else {
            sunGraphics2D.drawpipe = customViaShape;
            sunGraphics2D.fillpipe = customViaShape;
            sunGraphics2D.shapepipe = customShape;
            sunGraphics2D.textpipe = clippedByShape ? clippedCustomText : customText;
        }
    }

    /** XOR with a paint ({@code AlphaPaintPipe}'s way) instead of a custom Composite ({@code GeneralCompositePipe}'s). */
    private final boolean xorWithPaint;

    private SKCompositePipe(boolean xorWithPaint) {
        this.xorWithPaint = xorWithPaint;
    }

    /** One sequence of tiles: the destination region of a shape, read back into a buffer. */
    private static final class Context {
        SunGraphics2D sunGraphics2D;
        SurfaceData destination;
        PaintContext paintContext;
        CompositeContext compositeContext;   // a custom Composite's; null for XOR with a paint
        ColorModel colorModel;               // the destination's
        int bufferX, bufferY, bufferWidth, bufferHeight;   // the buffer's region on the destination
        BufferedImage buffer;                // null: nothing to draw
        SurfaceData bufferData;
        boolean drawn;
    }

    @Override
    public Object startSequence(SunGraphics2D sunGraphics2D, Shape shape, Rectangle deviceBounds, int[] deviceBox) {
        Context context = new Context();
        context.sunGraphics2D = sunGraphics2D;
        context.destination = sunGraphics2D.getSurfaceData();
        context.colorModel = sunGraphics2D.getDeviceColorModel();
        RenderingHints hints = sunGraphics2D.getRenderingHints();
        context.paintContext = sunGraphics2D.paint.createContext(context.colorModel, deviceBounds, shape.getBounds2D(),
                                                                 sunGraphics2D.cloneTransform(), hints);
        if (!xorWithPaint) {
            context.compositeContext = sunGraphics2D.composite.createContext(context.paintContext.getColorModel(),
                                                                             context.colorModel, hints);
        }
        // the bounds of the tiles to come, within the clip (glyph bounds are not clipped)
        Region clip = sunGraphics2D.getCompClip();
        int x1 = Math.max(deviceBox[0], clip.getLoX()), y1 = Math.max(deviceBox[1], clip.getLoY());
        int x2 = Math.min(deviceBox[2], clip.getHiX()), y2 = Math.min(deviceBox[3], clip.getHiY());
        if (x2 > x1 && y2 > y1) {
            context.bufferX = x1;
            context.bufferY = y1;
            context.bufferWidth = x2 - x1;
            context.bufferHeight = y2 - y1;
            ColorModel colorModel = context.colorModel;
            context.buffer = new BufferedImage(colorModel,
                                               colorModel.createCompatibleWritableRaster(context.bufferWidth, context.bufferHeight),
                                               colorModel.isAlphaPremultiplied(), null);
            context.bufferData = BufImgSurfaceData.createData(context.buffer);
            readBack(context);
        }
        return context;
    }

    private static void readBack(Context context) {
        SurfaceData destination = context.destination;
        SurfaceData bufferData = context.bufferData;
        int x = context.bufferX, y = context.bufferY, width = context.bufferWidth, height = context.bufferHeight;
        SurfaceType bufferType = bufferData.getSurfaceType();
        if (bufferType == SurfaceType.IntRgb) {
            surfaceToIntRgb.Blit(destination, bufferData, AlphaComposite.Src, null, x, y, 0, 0, width, height);
            return;
        }
        if (bufferType == SurfaceType.IntArgbPre) {
            Blit.getFromCache(destination.getSurfaceType(), CompositeType.SrcNoEa, bufferType)
                .Blit(destination, bufferData, AlphaComposite.Src, null, x, y, 0, 0, width, height);
            return;
        }
        // another color model (a window on a display that is not 24-bit): through premultiplied ARGB
        BufferedImage argbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        SurfaceData argbData = BufImgSurfaceData.createData(argbImage);
        Blit.getFromCache(destination.getSurfaceType(), CompositeType.SrcNoEa, SurfaceType.IntArgbPre)
            .Blit(destination, argbData, AlphaComposite.Src, null, x, y, 0, 0, width, height);
        Blit.getFromCache(SurfaceType.IntArgbPre, CompositeType.SrcNoEa, bufferType)
            .Blit(argbData, bufferData, AlphaComposite.Src, null, 0, 0, 0, 0, width, height);
    }

    @Override
    public boolean needTile(Object context, int x, int y, int width, int height) {
        return true;
    }

    /**
     * @param coverageTile the tile's coverage, or {@code null} for full coverage
     * @param tileScan     the tile's row stride
     */
    @Override
    public void renderPathTile(Object contextObject, byte[] coverageTile, int offset, int tileScan,
                               int x, int y, int width, int height) {
        Context context = (Context) contextObject;
        if (context.buffer == null) {
            return;
        }
        // the part of the tile in the buffer (all of it but for glyphs beyond the clip)
        int x1 = Math.max(x, context.bufferX), y1 = Math.max(y, context.bufferY);
        int x2 = Math.min(x + width, context.bufferX + context.bufferWidth);
        int y2 = Math.min(y + height, context.bufferY + context.bufferHeight);
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        offset += (y1 - y) * tileScan + (x1 - x);
        context.drawn = true;
        if (xorWithPaint) {
            xorTile(context, coverageTile, offset, tileScan, x1, y1, x2 - x1, y2 - y1);
        } else {
            composeTile(context, coverageTile, offset, tileScan, x1, y1, x2 - x1, y2 - y1);
        }
    }

    /** {@code GeneralCompositePipe.renderPathTile} on the buffer. */
    private static void composeTile(Context context, byte[] coverageTile, int offset, int tileScan,
                                    int x, int y, int width, int height) {
        Raster paintRaster = context.paintContext.getRaster(x, y, width, height);
        WritableRaster destinationIn = context.buffer.getRaster()
                .createWritableChild(x - context.bufferX, y - context.bufferY, width, height, 0, 0, null);
        if (coverageTile == null) {
            context.compositeContext.compose(paintRaster, destinationIn, destinationIn);
            return;
        }
        WritableRaster destinationOut = destinationIn.createCompatibleWritableRaster();
        context.compositeContext.compose(paintRaster, destinationIn, destinationOut);
        ColorModel colorModel = context.colorModel;
        BufferedImage result = new BufferedImage(colorModel, destinationOut, colorModel.isAlphaPremultiplied(), null);
        SurfaceData resultData = BufImgSurfaceData.createData(result);
        MaskBlit.getFromCache(resultData.getSurfaceType(), CompositeType.SrcNoEa, context.bufferData.getSurfaceType())
                .MaskBlit(resultData, context.bufferData, AlphaComposite.Src, null, 0, 0,
                          x - context.bufferX, y - context.bufferY, width, height, coverageTile, offset, tileScan);
    }

    /** The side of the rasters the paint context is asked for ({@code AlphaPaintPipe.TILE_SIZE}). */
    private static final int PAINT_TILE = 32;

    /** {@code AlphaPaintPipe.renderPathTile} on the buffer. */
    private static void xorTile(Context context, byte[] coverageTile, int offset, int tileScan,
                                int x, int y, int width, int height) {
        SunGraphics2D sunGraphics2D = context.sunGraphics2D;
        ColorModel paintModel = context.paintContext.getColorModel();
        CompositeType compositeType = sunGraphics2D.imageComp;
        if (CompositeType.SrcOverNoEa.equals(compositeType) && paintModel.getTransparency() == Transparency.OPAQUE) {
            compositeType = CompositeType.SrcNoEa;
        }
        for (int rowOffset = 0; rowOffset < height; rowOffset += PAINT_TILE) {
            int tileY = y + rowOffset;
            int tileHeight = Math.min(height - rowOffset, PAINT_TILE);
            for (int columnOffset = 0; columnOffset < width; columnOffset += PAINT_TILE) {
                int tileX = x + columnOffset;
                int tileWidth = Math.min(width - columnOffset, PAINT_TILE);
                Raster paintRaster = context.paintContext.getRaster(tileX, tileY, tileWidth, tileHeight);
                if (paintRaster.getMinX() != 0 || paintRaster.getMinY() != 0) {
                    paintRaster = paintRaster.createTranslatedChild(0, 0);
                }
                BufferedImage paintImage = new BufferedImage(paintModel, (WritableRaster) paintRaster,
                                                             paintModel.isAlphaPremultiplied(), null);
                SurfaceData paintData = BufImgSurfaceData.createData(paintImage);
                int bufferX = tileX - context.bufferX, bufferY = tileY - context.bufferY;
                if (coverageTile == null) {
                    Blit.getFromCache(paintData.getSurfaceType(), compositeType, context.bufferData.getSurfaceType())
                        .Blit(paintData, context.bufferData, sunGraphics2D.composite, null, 0, 0,
                              bufferX, bufferY, tileWidth, tileHeight);
                } else {
                    MaskBlit.getFromCache(paintData.getSurfaceType(), compositeType, context.bufferData.getSurfaceType())
                            .MaskBlit(paintData, context.bufferData, sunGraphics2D.composite, null, 0, 0,
                                      bufferX, bufferY, tileWidth, tileHeight,
                                      coverageTile, offset + rowOffset * tileScan + columnOffset, tileScan);
                }
            }
        }
    }

    @Override
    public void skipTile(Object context, int x, int y) {
    }

    @Override
    public void endSequence(Object contextObject) {
        Context context = (Context) contextObject;
        try {
            if (context.buffer != null && context.drawn) {
                Blit.getFromCache(context.bufferData.getSurfaceType(), CompositeType.SrcNoEa, context.destination.getSurfaceType())
                    .Blit(context.bufferData, context.destination, AlphaComposite.Src, null, 0, 0,
                          context.bufferX, context.bufferY, context.bufferWidth, context.bufferHeight);
            }
        } finally {
            context.paintContext.dispose();
            if (context.compositeContext != null) {
                context.compositeContext.dispose();
            }
        }
    }
}
