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

import sun.java2d.SurfaceData;
import sun.java2d.loops.Blit;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.GraphicsPrimitiveMgr;
import sun.java2d.loops.ScaledBlit;
import sun.java2d.loops.SurfaceType;
import sun.java2d.loops.TransformBlit;
import sun.java2d.pipe.Region;
import sun.java2d.pipe.RenderBuffer;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.lang.annotation.Native;
import java.lang.ref.WeakReference;

import static sun.java2d.pipe.BufferedOpCodes.BLIT;
import static sun.java2d.pipe.BufferedOpCodes.SURFACE_TO_SW_BLIT;

/**
 * Blit loops of the Skia backend. Same wire format as {@code MTLBlitLoops}:
 * a packed parameter word, source/destination rectangles and the native ops of
 * both surfaces. Software sources are read directly from their rasters on the
 * flusher thread; Skia surfaces are wrapped as images of the same texture.
 */
final class SKBlitLoops {

    static void register() {
        Blit blitIntArgbPreToSurface =
                new SKSoftwareToSurfaceBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);
        TransformBlit transformBlitIntArgbPreToSurface =
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);
        SKSurfaceToSoftwareBlit blitSurfaceToIntArgbPre =
                new SKSurfaceToSoftwareBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);
        Blit blitIntArgbPreToTexture =
                new SKSoftwareToSurfaceBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE,
                                            SKSurfaceData.SKTexture, CompositeType.SrcNoEa);

        GraphicsPrimitive[] primitives = {
                // surface->surface (RTT is a subtype of SKSurface)
                new SKSurfaceToSurfaceBlit(SKSurfaceData.SKSurface),
                new SKSurfaceToSurfaceScaledBlit(SKSurfaceData.SKSurface),
                new SKSurfaceToSurfaceTransformBlit(SKSurfaceData.SKSurface),
                // cached copies of software images (SKSurfaceDataProxy)
                new SKSurfaceToSurfaceBlit(SKSurfaceData.SKTexture),
                new SKSurfaceToSurfaceScaledBlit(SKSurfaceData.SKTexture),
                new SKSurfaceToSurfaceTransformBlit(SKSurfaceData.SKTexture),
                blitIntArgbPreToTexture,
                new SKSoftwareToSurfaceBlit(SurfaceType.IntArgb, SKSurfaceData.PF_INT_ARGB, SKSurfaceData.SKTexture, CompositeType.SrcNoEa),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntRgb,  SKSurfaceData.PF_INT_RGB,  SKSurfaceData.SKTexture, CompositeType.SrcNoEa),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntRgbx, SKSurfaceData.PF_INT_RGBX, SKSurfaceData.SKTexture, CompositeType.SrcNoEa),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntBgr,  SKSurfaceData.PF_INT_BGR,  SKSurfaceData.SKTexture, CompositeType.SrcNoEa),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntBgrx, SKSurfaceData.PF_INT_BGRX, SKSurfaceData.SKTexture, CompositeType.SrcNoEa),
                new SKGeneralBlit(SKSurfaceData.SKTexture, CompositeType.SrcNoEa, blitIntArgbPreToTexture),

                // surface->software
                new SKSurfaceToSoftwareBlit(SurfaceType.IntArgb, SKSurfaceData.PF_INT_ARGB),
                blitSurfaceToIntArgbPre,

                // software->surface
                blitIntArgbPreToSurface,
                new SKSoftwareToSurfaceBlit(SurfaceType.IntArgb,  SKSurfaceData.PF_INT_ARGB),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntRgb,   SKSurfaceData.PF_INT_RGB),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntRgbx,  SKSurfaceData.PF_INT_RGBX),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntBgr,   SKSurfaceData.PF_INT_BGR),
                new SKSoftwareToSurfaceBlit(SurfaceType.IntBgrx,  SKSurfaceData.PF_INT_BGRX),
                new SKGeneralBlit(SKSurfaceData.SKSurface, CompositeType.AnyAlpha, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SKSurfaceData.SKSurface, blitSurfaceToIntArgbPre, blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SKSurfaceData.SKTexture, blitSurfaceToIntArgbPre, blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SurfaceType.Any,
                        Blit.getFromCache(SurfaceType.Any, CompositeType.SrcNoEa, SurfaceType.IntArgbPre),
                        blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),

                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE),
                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntArgb,    SKSurfaceData.PF_INT_ARGB),
                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntRgb,     SKSurfaceData.PF_INT_RGB),
                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntRgbx,    SKSurfaceData.PF_INT_RGBX),
                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntBgr,     SKSurfaceData.PF_INT_BGR),
                new SKSoftwareToSurfaceScaledBlit(SurfaceType.IntBgrx,    SKSurfaceData.PF_INT_BGRX),

                transformBlitIntArgbPreToSurface,
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntArgb,  SKSurfaceData.PF_INT_ARGB),
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntRgb,   SKSurfaceData.PF_INT_RGB),
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntRgbx,  SKSurfaceData.PF_INT_RGBX),
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntBgr,   SKSurfaceData.PF_INT_BGR),
                new SKSoftwareToSurfaceTransformBlit(SurfaceType.IntBgrx,  SKSurfaceData.PF_INT_BGRX),
                new SKGeneralTransformBlit(transformBlitIntArgbPreToSurface),
        };
        GraphicsPrimitiveMgr.register(primitives);
    }

    /**
     * A Skia surface read back into an IntRgb raster as the stored bytes are (premultiplied,
     * which on an opaque surface is what is presented), for SKCompositePipe; not registered.
     */
    static Blit surfaceToIntRgb() {
        return new SKSurfaceToSoftwareBlit(SurfaceType.IntRgb, SKSurfaceData.PF_INT_ARGB_PRE);
    }

    // Packed parameter layout (shared with the native interpreter via the generated header)
    @Native private static final int OFFSET_SRCTYPE = 16;
    @Native private static final int OFFSET_HINT    =  8;
    @Native private static final int OFFSET_XFORM   =  1;
    @Native private static final int OFFSET_ISOBLIT =  0;

    /**
     * @param isoBlit      whether the source is a Skia surface too
     * @param transformed  whether the blit is under a transform
     * @param hint         the interpolation hint ({@code AffineTransformOp.TYPE_*})
     * @param sourceFormat a software source's pixel format ({@code SKSurfaceData.PF_*}); 0 for a Skia source
     */
    private static int createPackedParams(boolean isoBlit, boolean transformed, int hint, int sourceFormat) {
        return ((sourceFormat << OFFSET_SRCTYPE) |
                (hint << OFFSET_HINT) |
                ((transformed ? 1 : 0) << OFFSET_XFORM) |
                ((isoBlit ? 1 : 0) << OFFSET_ISOBLIT));
    }

    /*
     * The BLIT entry: the opcode and the packed parameters, the source rectangle (four ints),
     * the destination rectangle (four doubles), then the native ops of the source and the
     * destination; the first double is 24 bytes in, and it is aligned to that.
     */
    private static final int BLIT_FIRST_DOUBLE_OFFSET = 6 * Integer.BYTES;
    private static final int BLIT_ENTRY_BYTES = BLIT_FIRST_DOUBLE_OFFSET + 4 * Double.BYTES + 2 * Long.BYTES;

    @SuppressWarnings("DuplicatedCode")   // the BLIT entry every accelerated pipeline writes the same way (MTL, D3D, OGL)
    private static void enqueueBlit(SKRenderQueue renderQueue, SurfaceData source, SurfaceData destination,
                                    int packedParams,
                                    int sx1, int sy1, int sx2, int sy2,
                                    double dx1, double dy1, double dx2, double dy2) {
        RenderBuffer buffer = renderQueue.getBuffer();
        renderQueue.ensureCapacityAndAlignment(BLIT_ENTRY_BYTES, BLIT_FIRST_DOUBLE_OFFSET);
        buffer.putInt(BLIT);
        buffer.putInt(packedParams);
        buffer.putInt(sx1).putInt(sy1);
        buffer.putInt(sx2).putInt(sy2);
        buffer.putDouble(dx1).putDouble(dy1);
        buffer.putDouble(dx2).putDouble(dy2);
        buffer.putLong(source.getNativeOps());
        buffer.putLong(destination.getNativeOps());
    }

    /**
     * Software source to Skia surface.
     *
     * @param sourceFormat the source's pixel format ({@code SKSurfaceData.PF_*})
     */
    static void softwareToSurface(SurfaceData source, SurfaceData destination,
                                  Composite composite, Region clip,
                                  AffineTransform transform, int hint,
                                  int sx1, int sy1, int sx2, int sy2,
                                  double dx1, double dy1, double dx2, double dy2,
                                  int sourceFormat) {
        int contextFlags = 0;
        if (source.getTransparency() == Transparency.OPAQUE) {
            contextFlags |= SKContext.SRC_IS_OPAQUE;
        }
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            renderQueue.addReference(source);
            SKSurfaceData skiaDestination = (SKSurfaceData) destination;
            SKContext.validateContext(skiaDestination, skiaDestination, clip, composite, transform, null, null, contextFlags);
            int packedParams = createPackedParams(false, transform != null, hint, sourceFormat);
            enqueueBlit(renderQueue, source, destination, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            // the source raster is only guaranteed to stay valid for this call
            renderQueue.flushNow();
        } finally {
            renderQueue.unlock();
        }
    }

    /** Skia surface to Skia surface. */
    static void surfaceToSurface(SurfaceData source, SurfaceData destination,
                                 Composite composite, Region clip,
                                 AffineTransform transform, int hint,
                                 int sx1, int sy1, int sx2, int sy2,
                                 double dx1, double dy1, double dx2, double dy2) {
        int contextFlags = 0;
        if (source.getTransparency() == Transparency.OPAQUE) {
            contextFlags |= SKContext.SRC_IS_OPAQUE;
        }
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            SKSurfaceData skiaSource = (SKSurfaceData) source;
            SKSurfaceData skiaDestination = (SKSurfaceData) destination;
            SKContext.validateContext(skiaSource, skiaDestination, clip, composite, transform, null, null, contextFlags);
            int packedParams = createPackedParams(true, transform != null, hint, 0);
            enqueueBlit(renderQueue, source, destination, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            if (skiaSource.getType() != SKSurfaceData.TEXTURE && skiaDestination.isOnScreen()) {
                // Only a copy from a rendered surface to the screen has to show up
                // at once; cached images wait for the next flush, as on Metal, so
                // that Swing stays responsive while icons are drawn.
                renderQueue.flushNow();
            }
        } finally {
            renderQueue.unlock();
        }
    }
}

final class SKSurfaceToSurfaceBlit extends Blit {
    SKSurfaceToSurfaceBlit(SurfaceType sourceType) {
        super(sourceType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Blit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                     int sx, int sy, int dx, int dy, int width, int height) {
        SKBlitLoops.surfaceToSurface(source, destination, composite, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx + width, sy + height, dx, dy, dx + width, dy + height);
    }
}

final class SKSurfaceToSurfaceScaledBlit extends ScaledBlit {
    SKSurfaceToSurfaceScaledBlit(SurfaceType sourceType) {
        super(sourceType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Scale(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                      int sx1, int sy1, int sx2, int sy2,
                      double dx1, double dy1, double dx2, double dy2) {
        SKBlitLoops.surfaceToSurface(source, destination, composite, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    }
}

final class SKSurfaceToSurfaceTransformBlit extends TransformBlit {
    SKSurfaceToSurfaceTransformBlit(SurfaceType sourceType) {
        super(sourceType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Transform(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                          AffineTransform transform, int hint, int sx, int sy, int dx, int dy, int width, int height) {
        SKBlitLoops.surfaceToSurface(source, destination, composite, clip, transform, hint,
                sx, sy, sx + width, sy + height, dx, dy, dx + width, dy + height);
    }
}

final class SKSurfaceToSoftwareBlit extends Blit {
    /*
     * The SURFACE_TO_SW_BLIT entry: the opcode, the source and destination positions, the
     * size and the destination's pixel format (eight ints), then the native ops of the source
     * and the destination; the first long is 32 bytes in, and it is aligned to that.
     */
    private static final int FIRST_LONG_OFFSET = 8 * Integer.BYTES;
    private static final int ENTRY_BYTES = FIRST_LONG_OFFSET + 2 * Long.BYTES;

    /** The destination's pixel format ({@code SKSurfaceData.PF_*}). */
    private final int pixelFormat;
    /** The last intermediate copy of a source under a complex clip, reused while it fits. */
    private WeakReference<SurfaceData> cachedSource;

    SKSurfaceToSoftwareBlit(SurfaceType destinationType, int pixelFormat) {
        super(SKSurfaceData.SKSurface, CompositeType.SrcNoEa, destinationType);
        this.pixelFormat = pixelFormat;
    }

    private synchronized void complexClipBlit(SurfaceData source, SurfaceData destination, Composite composite,
                                              Region clip, int sx, int sy, int dx, int dy, int width, int height) {
        SurfaceData previousCopy = cachedSource != null ? cachedSource.get() : null;
        int imageType = pixelFormat == SKSurfaceData.PF_INT_ARGB_PRE ?
                BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB;
        source = convertFrom(this, source, sx, sy, width, height, previousCopy, imageType);
        Blit blit = Blit.getFromCache(source.getSurfaceType(), CompositeType.SrcNoEa, destination.getSurfaceType());
        blit.Blit(source, destination, composite, clip, 0, 0, dx, dy, width, height);
        if (source != previousCopy) {
            cachedSource = new WeakReference<>(source);
        }
    }

    @Override
    @SuppressWarnings({"DuplicatedCode",              // the clip handling every accelerated pipeline's read-back blit spells out (MTL, D3D, OGL)
                       "SuspiciousNameCombination"})  // RenderBuffer.putInt names its one parameter x
    public void Blit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                     int sx, int sy, int dx, int dy, int width, int height) {
        if (clip != null) {
            clip = clip.getIntersectionXYWH(dx, dy, width, height);
            if (clip.isEmpty()) {
                return;
            }
            sx += clip.getLoX() - dx;
            sy += clip.getLoY() - dy;
            dx = clip.getLoX();
            dy = clip.getLoY();
            width = clip.getWidth();
            height = clip.getHeight();
            if (!clip.isRectangular()) {
                complexClipBlit(source, destination, composite, clip, sx, sy, dx, dy, width, height);
                return;
            }
        }
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            renderQueue.addReference(destination);
            RenderBuffer buffer = renderQueue.getBuffer();
            SKContext.validateContext((SKSurfaceData) source);
            renderQueue.ensureCapacityAndAlignment(ENTRY_BYTES, FIRST_LONG_OFFSET);
            buffer.putInt(SURFACE_TO_SW_BLIT);
            buffer.putInt(sx).putInt(sy);
            buffer.putInt(dx).putInt(dy);
            buffer.putInt(width).putInt(height);
            buffer.putInt(pixelFormat);
            buffer.putLong(source.getNativeOps());
            buffer.putLong(destination.getNativeOps());
            renderQueue.flushNow();
        } finally {
            renderQueue.unlock();
        }
    }
}

final class SKSoftwareToSurfaceBlit extends Blit {
    /** The source's pixel format ({@code SKSurfaceData.PF_*}). */
    private final int pixelFormat;
    SKSoftwareToSurfaceBlit(SurfaceType sourceType, int pixelFormat) {
        this(sourceType, pixelFormat, SKSurfaceData.SKSurface, CompositeType.AnyAlpha);
    }
    SKSoftwareToSurfaceBlit(SurfaceType sourceType, int pixelFormat, SurfaceType destinationType, CompositeType compositeType) {
        super(sourceType, compositeType, destinationType);
        this.pixelFormat = pixelFormat;
    }
    @Override
    public void Blit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                     int sx, int sy, int dx, int dy, int width, int height) {
        SKBlitLoops.softwareToSurface(source, destination, composite, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx + width, sy + height, dx, dy, dx + width, dy + height, pixelFormat);
    }
}

final class SKSoftwareToSurfaceScaledBlit extends ScaledBlit {
    /** The source's pixel format ({@code SKSurfaceData.PF_*}). */
    private final int pixelFormat;
    SKSoftwareToSurfaceScaledBlit(SurfaceType sourceType, int pixelFormat) {
        super(sourceType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.pixelFormat = pixelFormat;
    }
    @Override
    public void Scale(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                      int sx1, int sy1, int sx2, int sy2,
                      double dx1, double dy1, double dx2, double dy2) {
        SKBlitLoops.softwareToSurface(source, destination, composite, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2, pixelFormat);
    }
}

final class SKSoftwareToSurfaceTransformBlit extends TransformBlit {
    /** The source's pixel format ({@code SKSurfaceData.PF_*}). */
    private final int pixelFormat;
    SKSoftwareToSurfaceTransformBlit(SurfaceType sourceType, int pixelFormat) {
        super(sourceType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.pixelFormat = pixelFormat;
    }
    @Override
    public void Transform(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                          AffineTransform transform, int hint, int sx, int sy, int dx, int dy, int width, int height) {
        SKBlitLoops.softwareToSurface(source, destination, composite, clip, transform, hint,
                sx, sy, sx + width, sy + height, dx, dy, dx + width, dy + height, pixelFormat);
    }
}

/** Converts any source to IntArgbPre first, then blits (mirrors MTLGeneralBlit). */
final class SKGeneralBlit extends Blit {
    /** The IntArgbPre-to-surface blit the converted source goes through. */
    private final Blit blitConverted;
    /** The last converted copy, reused while it fits. */
    private WeakReference<SurfaceData> cachedSource;

    SKGeneralBlit(SurfaceType destinationType, CompositeType compositeType, Blit blitConverted) {
        super(SurfaceType.Any, compositeType, destinationType);
        this.blitConverted = blitConverted;
    }

    @Override
    public synchronized void Blit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                                  int sx, int sy, int dx, int dy, int width, int height) {
        Blit convertSource = Blit.getFromCache(source.getSurfaceType(), CompositeType.SrcNoEa, SurfaceType.IntArgbPre);
        SurfaceData previousCopy = cachedSource != null ? cachedSource.get() : null;
        source = convertFrom(convertSource, source, sx, sy, width, height, previousCopy, BufferedImage.TYPE_INT_ARGB_PRE);
        blitConverted.Blit(source, destination, composite, clip, 0, 0, dx, dy, width, height);
        if (source != previousCopy) {
            cachedSource = new WeakReference<>(source);
        }
    }
}

final class SKGeneralTransformBlit extends TransformBlit {
    /** The IntArgbPre-to-surface blit the converted source goes through. */
    private final TransformBlit blitConverted;
    /** The last converted copy, reused while it fits. */
    private WeakReference<SurfaceData> cachedSource;

    SKGeneralTransformBlit(TransformBlit blitConverted) {
        super(SurfaceType.Any, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.blitConverted = blitConverted;
    }

    @Override
    public synchronized void Transform(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                                       AffineTransform transform, int hint, int sx, int sy,
                                       int dx, int dy, int width, int height) {
        Blit convertSource = Blit.getFromCache(source.getSurfaceType(), CompositeType.SrcNoEa, SurfaceType.IntArgbPre);
        SurfaceData previousCopy = cachedSource != null ? cachedSource.get() : null;
        source = convertFrom(convertSource, source, sx, sy, width, height, previousCopy, BufferedImage.TYPE_INT_ARGB_PRE);
        blitConverted.Transform(source, destination, composite, clip, transform, hint, 0, 0, dx, dy, width, height);
        if (source != previousCopy) {
            cachedSource = new WeakReference<>(source);
        }
    }
}

/** Arbitrary composites: read back, composite in software, write back (mirrors MTLAnyCompositeBlit). */
final class SKAnyCompositeBlit extends Blit {
    /** The last read-back buffer and converted source, reused while they fit. */
    private WeakReference<SurfaceData> cachedDestinationBuffer;
    private WeakReference<SurfaceData> cachedSource;
    /** Source to IntArgbPre; {@code null} when the source already is. */
    private final Blit convertSource;
    /** The destination read back into IntArgbPre. */
    private final Blit convertDestination;
    /** The composited buffer written back to the destination. */
    private final Blit convertResult;

    SKAnyCompositeBlit(SurfaceType sourceType, Blit convertSource, Blit convertDestination, Blit convertResult) {
        super(sourceType, CompositeType.Any, SKSurfaceData.SKSurface);
        this.convertSource = convertSource;
        this.convertDestination = convertDestination;
        this.convertResult = convertResult;
    }

    @Override
    @SuppressWarnings("DuplicatedCode")   // the read-back/composite/write-back every accelerated pipeline spells out (MTL, D3D, OGL)
    public synchronized void Blit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                                  int sx, int sy, int dx, int dy, int width, int height) {
        if (convertSource != null) {
            SurfaceData previousSource = cachedSource != null ? cachedSource.get() : null;
            source = convertFrom(convertSource, source, sx, sy, width, height, previousSource, BufferedImage.TYPE_INT_ARGB_PRE);
            if (source != previousSource) {
                cachedSource = new WeakReference<>(source);
            }
            // the converted copy holds the source region at its origin (MTLAnyCompositeBlit
            // reads it at sx, sy: a part of an image beyond the copy's bounds throws)
            sx = 0;
            sy = 0;
        }
        SurfaceData previousBuffer = cachedDestinationBuffer != null ? cachedDestinationBuffer.get() : null;
        SurfaceData destinationBuffer = convertFrom(convertDestination, destination, dx, dy, width, height, previousBuffer,
                                                    BufferedImage.TYPE_INT_ARGB_PRE);
        Region bufferClip = clip == null ? null : clip.getTranslatedRegion(-dx, -dy);
        Blit compositeBlit = Blit.getFromCache(source.getSurfaceType(), CompositeType.Any, destinationBuffer.getSurfaceType());
        compositeBlit.Blit(source, destinationBuffer, composite, bufferClip, sx, sy, 0, 0, width, height);
        if (destinationBuffer != previousBuffer) {
            cachedDestinationBuffer = new WeakReference<>(destinationBuffer);
        }
        convertResult.Blit(destinationBuffer, destination, AlphaComposite.Src, clip, 0, 0, dx, dy, width, height);
    }
}
