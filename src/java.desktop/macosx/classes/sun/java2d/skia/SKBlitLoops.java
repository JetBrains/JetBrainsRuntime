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
                new SKSwToSurfaceBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);
        TransformBlit transformBlitIntArgbPreToSurface =
                new SKSwToSurfaceTransform(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);
        SKSurfaceToSwBlit blitSurfaceToIntArgbPre =
                new SKSurfaceToSwBlit(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE);

        GraphicsPrimitive[] primitives = {
                // surface->surface (RTT is a subtype of SKSurface)
                new SKSurfaceToSurfaceBlit(),
                new SKSurfaceToSurfaceScale(),
                new SKSurfaceToSurfaceTransform(),

                // surface->sw
                new SKSurfaceToSwBlit(SurfaceType.IntArgb, SKSurfaceData.PF_INT_ARGB),
                blitSurfaceToIntArgbPre,

                // sw->surface
                blitIntArgbPreToSurface,
                new SKSwToSurfaceBlit(SurfaceType.IntArgb,  SKSurfaceData.PF_INT_ARGB),
                new SKSwToSurfaceBlit(SurfaceType.IntRgb,   SKSurfaceData.PF_INT_RGB),
                new SKSwToSurfaceBlit(SurfaceType.IntRgbx,  SKSurfaceData.PF_INT_RGBX),
                new SKSwToSurfaceBlit(SurfaceType.IntBgr,   SKSurfaceData.PF_INT_BGR),
                new SKSwToSurfaceBlit(SurfaceType.IntBgrx,  SKSurfaceData.PF_INT_BGRX),
                new SKGeneralBlit(SKSurfaceData.SKSurface, CompositeType.AnyAlpha, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SKSurfaceData.SKSurface, blitSurfaceToIntArgbPre, blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SKSurfaceData.SKTexture, blitSurfaceToIntArgbPre, blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),
                new SKAnyCompositeBlit(SurfaceType.Any,
                        Blit.getFromCache(SurfaceType.Any, CompositeType.SrcNoEa, SurfaceType.IntArgbPre),
                        blitSurfaceToIntArgbPre, blitIntArgbPreToSurface),

                new SKSwToSurfaceScale(SurfaceType.IntArgbPre, SKSurfaceData.PF_INT_ARGB_PRE),
                new SKSwToSurfaceScale(SurfaceType.IntArgb,    SKSurfaceData.PF_INT_ARGB),
                new SKSwToSurfaceScale(SurfaceType.IntRgb,     SKSurfaceData.PF_INT_RGB),
                new SKSwToSurfaceScale(SurfaceType.IntRgbx,    SKSurfaceData.PF_INT_RGBX),
                new SKSwToSurfaceScale(SurfaceType.IntBgr,     SKSurfaceData.PF_INT_BGR),
                new SKSwToSurfaceScale(SurfaceType.IntBgrx,    SKSurfaceData.PF_INT_BGRX),

                transformBlitIntArgbPreToSurface,
                new SKSwToSurfaceTransform(SurfaceType.IntArgb,  SKSurfaceData.PF_INT_ARGB),
                new SKSwToSurfaceTransform(SurfaceType.IntRgb,   SKSurfaceData.PF_INT_RGB),
                new SKSwToSurfaceTransform(SurfaceType.IntRgbx,  SKSurfaceData.PF_INT_RGBX),
                new SKSwToSurfaceTransform(SurfaceType.IntBgr,   SKSurfaceData.PF_INT_BGR),
                new SKSwToSurfaceTransform(SurfaceType.IntBgrx,  SKSurfaceData.PF_INT_BGRX),
                new SKGeneralTransformedBlit(transformBlitIntArgbPreToSurface),
        };
        GraphicsPrimitiveMgr.register(primitives);
    }

    // Packed parameter layout (shared with the native interpreter via the generated header)
    @Native private static final int OFFSET_SRCTYPE = 16;
    @Native private static final int OFFSET_HINT    =  8;
    @Native private static final int OFFSET_XFORM   =  1;
    @Native private static final int OFFSET_ISOBLIT =  0;

    private static int createPackedParams(boolean isoblit, boolean xform, int hint, int srctype) {
        return ((srctype << OFFSET_SRCTYPE) |
                (hint << OFFSET_HINT) |
                ((xform ? 1 : 0) << OFFSET_XFORM) |
                ((isoblit ? 1 : 0) << OFFSET_ISOBLIT));
    }

    private static void enqueueBlit(SKRenderQueue rq, SurfaceData src, SurfaceData dst,
                                    int packedParams,
                                    int sx1, int sy1, int sx2, int sy2,
                                    double dx1, double dy1, double dx2, double dy2) {
        RenderBuffer buf = rq.getBuffer();
        rq.ensureCapacityAndAlignment(72, 24);
        buf.putInt(BLIT);
        buf.putInt(packedParams);
        buf.putInt(sx1).putInt(sy1);
        buf.putInt(sx2).putInt(sy2);
        buf.putDouble(dx1).putDouble(dy1);
        buf.putDouble(dx2).putDouble(dy2);
        buf.putLong(src.getNativeOps());
        buf.putLong(dst.getNativeOps());
    }

    /** Software source to Skia surface. */
    static void Blit(SurfaceData srcData, SurfaceData dstData,
                     Composite comp, Region clip,
                     AffineTransform xform, int hint,
                     int sx1, int sy1, int sx2, int sy2,
                     double dx1, double dy1, double dx2, double dy2,
                     int srctype) {
        int ctxflags = 0;
        if (srcData.getTransparency() == Transparency.OPAQUE) {
            ctxflags |= SKContext.SRC_IS_OPAQUE;
        }
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            rq.addReference(srcData);
            SKSurfaceData skDst = (SKSurfaceData) dstData;
            SKContext.validateContext(skDst, skDst, clip, comp, xform, null, null, ctxflags);
            int packedParams = createPackedParams(false, xform != null, hint, srctype);
            enqueueBlit(rq, srcData, dstData, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            // the source raster is only guaranteed to stay valid for this call
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    /** Skia surface to Skia surface. */
    static void IsoBlit(SurfaceData srcData, SurfaceData dstData,
                        Composite comp, Region clip,
                        AffineTransform xform, int hint,
                        int sx1, int sy1, int sx2, int sy2,
                        double dx1, double dy1, double dx2, double dy2) {
        int ctxflags = 0;
        if (srcData.getTransparency() == Transparency.OPAQUE) {
            ctxflags |= SKContext.SRC_IS_OPAQUE;
        }
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            SKSurfaceData skSrc = (SKSurfaceData) srcData;
            SKSurfaceData skDst = (SKSurfaceData) dstData;
            SKContext.validateContext(skSrc, skDst, clip, comp, xform, null, null, ctxflags);
            int packedParams = createPackedParams(true, xform != null, hint, 0);
            enqueueBlit(rq, srcData, dstData, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            if (skSrc.getType() != SKSurfaceData.TEXTURE && skDst.isOnScreen()) {
                // Only a copy from a rendered surface to the screen has to show up
                // at once; cached images wait for the next flush, as on Metal, so
                // that Swing stays responsive while icons are drawn.
                rq.flushNow();
            }
        } finally {
            rq.unlock();
        }
    }
}

final class SKSurfaceToSurfaceBlit extends Blit {
    SKSurfaceToSurfaceBlit() {
        super(SKSurfaceData.SKSurface, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h) {
        SKBlitLoops.IsoBlit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h);
    }
}

final class SKSurfaceToSurfaceScale extends ScaledBlit {
    SKSurfaceToSurfaceScale() {
        super(SKSurfaceData.SKSurface, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Scale(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                      int sx1, int sy1, int sx2, int sy2,
                      double dx1, double dy1, double dx2, double dy2) {
        SKBlitLoops.IsoBlit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    }
}

final class SKSurfaceToSurfaceTransform extends TransformBlit {
    SKSurfaceToSurfaceTransform() {
        super(SKSurfaceData.SKSurface, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
    }
    @Override
    public void Transform(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                          AffineTransform at, int hint, int sx, int sy, int dx, int dy, int w, int h) {
        SKBlitLoops.IsoBlit(src, dst, comp, clip, at, hint,
                sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h);
    }
}

final class SKSurfaceToSwBlit extends Blit {
    private final int typeval;
    private WeakReference<SurfaceData> srcTmp;

    SKSurfaceToSwBlit(SurfaceType dstType, int typeval) {
        super(SKSurfaceData.SKSurface, CompositeType.SrcNoEa, dstType);
        this.typeval = typeval;
    }

    private synchronized void complexClipBlit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                                              int sx, int sy, int dx, int dy, int w, int h) {
        SurfaceData cachedSrc = srcTmp != null ? srcTmp.get() : null;
        int type = typeval == SKSurfaceData.PF_INT_ARGB_PRE ?
                BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB;
        src = convertFrom(this, src, sx, sy, w, h, cachedSrc, type);
        Blit performop = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, dst.getSurfaceType());
        performop.Blit(src, dst, comp, clip, 0, 0, dx, dy, w, h);
        if (src != cachedSrc) {
            srcTmp = new WeakReference<>(src);
        }
    }

    @Override
    public void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h) {
        if (clip != null) {
            clip = clip.getIntersectionXYWH(dx, dy, w, h);
            if (clip.isEmpty()) {
                return;
            }
            sx += clip.getLoX() - dx;
            sy += clip.getLoY() - dy;
            dx = clip.getLoX();
            dy = clip.getLoY();
            w = clip.getWidth();
            h = clip.getHeight();
            if (!clip.isRectangular()) {
                complexClipBlit(src, dst, comp, clip, sx, sy, dx, dy, w, h);
                return;
            }
        }
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            rq.addReference(dst);
            RenderBuffer buf = rq.getBuffer();
            SKContext.validateContext((SKSurfaceData) src);
            rq.ensureCapacityAndAlignment(48, 32);
            buf.putInt(SURFACE_TO_SW_BLIT);
            buf.putInt(sx).putInt(sy);
            buf.putInt(dx).putInt(dy);
            buf.putInt(w).putInt(h);
            buf.putInt(typeval);
            buf.putLong(src.getNativeOps());
            buf.putLong(dst.getNativeOps());
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }
}

final class SKSwToSurfaceBlit extends Blit {
    private final int typeval;
    SKSwToSurfaceBlit(SurfaceType srcType, int typeval) {
        super(srcType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.typeval = typeval;
    }
    @Override
    public void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h) {
        SKBlitLoops.Blit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h, typeval);
    }
}

final class SKSwToSurfaceScale extends ScaledBlit {
    private final int typeval;
    SKSwToSurfaceScale(SurfaceType srcType, int typeval) {
        super(srcType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.typeval = typeval;
    }
    @Override
    public void Scale(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                      int sx1, int sy1, int sx2, int sy2,
                      double dx1, double dy1, double dx2, double dy2) {
        SKBlitLoops.Blit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2, typeval);
    }
}

final class SKSwToSurfaceTransform extends TransformBlit {
    private final int typeval;
    SKSwToSurfaceTransform(SurfaceType srcType, int typeval) {
        super(srcType, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.typeval = typeval;
    }
    @Override
    public void Transform(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                          AffineTransform at, int hint, int sx, int sy, int dx, int dy, int w, int h) {
        SKBlitLoops.Blit(src, dst, comp, clip, at, hint,
                sx, sy, sx + w, sy + h, dx, dy, dx + w, dy + h, typeval);
    }
}

/** Converts any source to IntArgbPre first, then blits (mirrors MTLGeneralBlit). */
final class SKGeneralBlit extends Blit {
    private final Blit performop;
    private WeakReference<SurfaceData> srcTmp;

    SKGeneralBlit(SurfaceType dstType, CompositeType compType, Blit performop) {
        super(SurfaceType.Any, compType, dstType);
        this.performop = performop;
    }

    @Override
    public synchronized void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                                  int sx, int sy, int dx, int dy, int w, int h) {
        Blit convertsrc = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, SurfaceType.IntArgbPre);
        SurfaceData cachedSrc = srcTmp != null ? srcTmp.get() : null;
        src = convertFrom(convertsrc, src, sx, sy, w, h, cachedSrc, BufferedImage.TYPE_INT_ARGB_PRE);
        performop.Blit(src, dst, comp, clip, 0, 0, dx, dy, w, h);
        if (src != cachedSrc) {
            srcTmp = new WeakReference<>(src);
        }
    }
}

final class SKGeneralTransformedBlit extends TransformBlit {
    private final TransformBlit performop;
    private WeakReference<SurfaceData> srcTmp;

    SKGeneralTransformedBlit(TransformBlit performop) {
        super(SurfaceType.Any, CompositeType.AnyAlpha, SKSurfaceData.SKSurface);
        this.performop = performop;
    }

    @Override
    public synchronized void Transform(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                                       AffineTransform at, int hint, int srcx, int srcy,
                                       int dstx, int dsty, int width, int height) {
        Blit convertsrc = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, SurfaceType.IntArgbPre);
        SurfaceData cachedSrc = srcTmp != null ? srcTmp.get() : null;
        src = convertFrom(convertsrc, src, srcx, srcy, width, height, cachedSrc, BufferedImage.TYPE_INT_ARGB_PRE);
        performop.Transform(src, dst, comp, clip, at, hint, 0, 0, dstx, dsty, width, height);
        if (src != cachedSrc) {
            srcTmp = new WeakReference<>(src);
        }
    }
}

/** Arbitrary composites: read back, composite in software, write back (mirrors MTLAnyCompositeBlit). */
final class SKAnyCompositeBlit extends Blit {
    private WeakReference<SurfaceData> dstTmp;
    private WeakReference<SurfaceData> srcTmp;
    private final Blit convertsrc;
    private final Blit convertdst;
    private final Blit convertresult;

    SKAnyCompositeBlit(SurfaceType srctype, Blit convertsrc, Blit convertdst, Blit convertresult) {
        super(srctype, CompositeType.Any, SKSurfaceData.SKSurface);
        this.convertsrc = convertsrc;
        this.convertdst = convertdst;
        this.convertresult = convertresult;
    }

    @Override
    public synchronized void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                                  int sx, int sy, int dx, int dy, int w, int h) {
        if (convertsrc != null) {
            SurfaceData cachedSrc = srcTmp != null ? srcTmp.get() : null;
            src = convertFrom(convertsrc, src, sx, sy, w, h, cachedSrc, BufferedImage.TYPE_INT_ARGB_PRE);
            if (src != cachedSrc) {
                srcTmp = new WeakReference<>(src);
            }
        }
        SurfaceData cachedDst = dstTmp != null ? dstTmp.get() : null;
        SurfaceData dstBuffer = convertFrom(convertdst, dst, dx, dy, w, h, cachedDst, BufferedImage.TYPE_INT_ARGB_PRE);
        Region bufferClip = clip == null ? null : clip.getTranslatedRegion(-dx, -dy);
        Blit performop = Blit.getFromCache(src.getSurfaceType(), CompositeType.Any, dstBuffer.getSurfaceType());
        performop.Blit(src, dstBuffer, comp, bufferClip, sx, sy, 0, 0, w, h);
        if (dstBuffer != cachedDst) {
            dstTmp = new WeakReference<>(dstBuffer);
        }
        convertresult.Blit(dstBuffer, dst, AlphaComposite.Src, clip, 0, 0, dx, dy, w, h);
    }
}
