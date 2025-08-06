/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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
package sun.java2d.vulkan;

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
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.hw.AccelSurface;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.lang.annotation.Native;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static sun.java2d.pipe.BufferedOpCodes.BLIT;
import static sun.java2d.pipe.BufferedOpCodes.SURFACE_TO_SW_BLIT;
import static java.awt.Transparency.OPAQUE;
import static java.awt.Transparency.TRANSLUCENT;

final class VKBlitLoops {

    static void register() {
        Blit blitIntArgbPreToSurface =
                new VKSwToSurfaceBlit(SurfaceType.IntArgbPre,
                        VKSurfaceData.PF_INT_ARGB_PRE);
        Blit blitIntArgbPreToTexture =
                new VKSwToTextureBlit(SurfaceType.IntArgbPre,
                        VKSurfaceData.PF_INT_ARGB_PRE);
        TransformBlit transformBlitIntArgbPreToSurface =
                new VKSwToSurfaceTransform(SurfaceType.IntArgbPre,
                        VKSurfaceData.PF_INT_ARGB_PRE);
        VKSurfaceToSwBlit blitSurfaceToIntArgbPre =
                new VKSurfaceToSwBlit(VKFormat.B8G8R8A8_UNORM, TRANSLUCENT); // TODO this is a placeholder.

        GraphicsPrimitive[] primitiveArray = {
                // sw->surface ops
                blitIntArgbPreToSurface,
                new VKSwToSurfaceBlit(SurfaceType.IntRgb,
                        VKSurfaceData.PF_INT_RGB),
                new VKSwToSurfaceBlit(SurfaceType.IntRgbx,
                        VKSurfaceData.PF_INT_RGBX),
                new VKSwToSurfaceBlit(SurfaceType.IntBgr,
                        VKSurfaceData.PF_INT_BGR),
                new VKSwToSurfaceBlit(SurfaceType.IntBgrx,
                        VKSurfaceData.PF_INT_BGRX),
                new VKGeneralBlit(VKSurfaceData.VKSurface,
                        CompositeType.AnyAlpha,
                        blitIntArgbPreToSurface),

                new VKAnyCompositeBlit(VKSurfaceData.VKSurface,
                        blitSurfaceToIntArgbPre,
                        blitSurfaceToIntArgbPre,
                        blitIntArgbPreToSurface),
                new VKAnyCompositeBlit(SurfaceType.Any,
                        null,
                        blitSurfaceToIntArgbPre,
                        blitIntArgbPreToSurface),

                new VKSwToSurfaceScale(SurfaceType.IntRgb,
                        VKSurfaceData.PF_INT_RGB),
                new VKSwToSurfaceScale(SurfaceType.IntRgbx,
                        VKSurfaceData.PF_INT_RGBX),
                new VKSwToSurfaceScale(SurfaceType.IntBgr,
                        VKSurfaceData.PF_INT_BGR),
                new VKSwToSurfaceScale(SurfaceType.IntBgrx,
                        VKSurfaceData.PF_INT_BGRX),
                new VKSwToSurfaceScale(SurfaceType.IntArgbPre,
                        VKSurfaceData.PF_INT_ARGB_PRE),

                new VKSwToSurfaceTransform(SurfaceType.IntRgb,
                        VKSurfaceData.PF_INT_RGB),
                new VKSwToSurfaceTransform(SurfaceType.IntRgbx,
                        VKSurfaceData.PF_INT_RGBX),
                new VKSwToSurfaceTransform(SurfaceType.IntBgr,
                        VKSurfaceData.PF_INT_BGR),
                new VKSwToSurfaceTransform(SurfaceType.IntBgrx,
                        VKSurfaceData.PF_INT_BGRX),
                transformBlitIntArgbPreToSurface,

                new VKGeneralTransformedBlit(transformBlitIntArgbPreToSurface),

                // sw->texture ops
                blitIntArgbPreToTexture,
                new VKSwToTextureBlit(SurfaceType.IntRgb,
                        VKSurfaceData.PF_INT_RGB),
                new VKSwToTextureBlit(SurfaceType.IntRgbx,
                        VKSurfaceData.PF_INT_RGBX),
                new VKSwToTextureBlit(SurfaceType.IntBgr,
                        VKSurfaceData.PF_INT_BGR),
                new VKSwToTextureBlit(SurfaceType.IntBgrx,
                        VKSurfaceData.PF_INT_BGRX),
                new VKGeneralBlit(VKSurfaceData.VKTexture,
                        CompositeType.SrcNoEa,
                        blitIntArgbPreToTexture),
        };

        List<GraphicsPrimitive> primitives = new ArrayList<>();
        Collections.addAll(primitives, primitiveArray);
        // Surface->Surface
        primitives.add(new VKSurfaceToSurfaceBlit(CompositeType.AnyAlpha));
        primitives.add(new VKSurfaceToSurfaceBlit(CompositeType.Xor));
        primitives.add(new VKSurfaceToSurfaceScale(CompositeType.AnyAlpha));
        primitives.add(new VKSurfaceToSurfaceScale(CompositeType.Xor));
        primitives.add(new VKSurfaceToSurfaceTransform(CompositeType.AnyAlpha));
        primitives.add(new VKSurfaceToSurfaceTransform(CompositeType.Xor));
        // Surface->Sw
        for (VKFormat format : VKFormat.values()) {
            primitives.add(new VKSurfaceToSwBlit(format, OPAQUE));
            if (format.isTranslucencyCapable()) {
                primitives.add(new VKSurfaceToSwBlit(format, TRANSLUCENT));
            }
        }

        GraphicsPrimitiveMgr.register(primitives.toArray(GraphicsPrimitive[]::new));
    }

    /**
     * The following offsets are used to pack the parameters in
     * createPackedParams().  (They are also used at the native level when
     * unpacking the params.)
     */
    @Native private static final int OFFSET_SRCTYPE = 16;
    @Native private static final int OFFSET_HINT    =  8;
    @Native private static final int OFFSET_XFORM   =  1;
    @Native private static final int OFFSET_ISOBLIT =  0;

    /**
     * Packs the given parameters into a single int value in order to save
     * space on the rendering queue.
     */
    private static int createPackedParams(boolean isoblit, boolean xform, int hint, int srctype) {
        return
                ((srctype           << OFFSET_SRCTYPE) |
                 (hint              << OFFSET_HINT   ) |
                 ((xform   ? 1 : 0) << OFFSET_XFORM  ) |
                 ((isoblit ? 1 : 0) << OFFSET_ISOBLIT));
    }

    /**
     * Enqueues a BLIT operation with the given parameters.  Note that the
     * RenderQueue lock must be held before calling this method.
     */
    private static void enqueueBlit(RenderQueue rq,
                                    SurfaceData src, SurfaceData dst,
                                    int packedParams,
                                    int sx1, int sy1,
                                    int sx2, int sy2,
                                    double dx1, double dy1,
                                    double dx2, double dy2)
    {
        // assert rq.lock.isHeldByCurrentThread();
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

    static void Blit(SurfaceData srcData, SurfaceData dstData,
                     Composite comp, Region clip,
                     AffineTransform xform, int hint,
                     int sx1, int sy1,
                     int sx2, int sy2,
                     double dx1, double dy1,
                     double dx2, double dy2,
                     int srctype, boolean texture)
    {
        int ctxflags = 0;
        if (srcData.getTransparency() == Transparency.OPAQUE) {
            ctxflags |= VKContext.SRC_IS_OPAQUE;
        }

        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            // make sure the RenderQueue keeps a hard reference to the
            // source (sysmem) SurfaceData to prevent it from being
            // disposed while the operation is processed on the QFT
            rq.addReference(srcData);

            VKSurfaceData vkDst = (VKSurfaceData)dstData;
            if (texture) {
                // make sure we have a current context before uploading
                // the sysmem data to the texture object
                VKGraphicsConfig gc = vkDst.getGraphicsConfig();
                VKContext.setScratchSurface(gc);
            } else {
                VKContext.validateContext(vkDst, vkDst,
                        clip, comp, xform, null, null,
                        ctxflags);
            }

            int packedParams = createPackedParams(false, xform != null, hint, srctype);
            enqueueBlit(rq, srcData, dstData,
                    packedParams,
                    sx1, sy1, sx2, sy2,
                    dx1, dy1, dx2, dy2);

            // always flush immediately, since we (currently) have no means
            // of tracking changes to the system memory surface
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    /**
     * Note: The srcImg and biop parameters are only used when invoked
     * from the VKBufImgOps.renderImageWithOp() method; in all other cases,
     * this method can be called with null values for those two parameters,
     * and they will be effectively ignored.
     */
    static void IsoBlit(SurfaceData srcData, SurfaceData dstData,
                        BufferedImage srcImg, BufferedImageOp biop,
                        Composite comp, Region clip,
                        AffineTransform xform, int hint,
                        int sx1, int sy1,
                        int sx2, int sy2,
                        double dx1, double dy1,
                        double dx2, double dy2) {
        int ctxflags = 0;
        if (srcData.getTransparency() == Transparency.OPAQUE) {
            ctxflags |= VKContext.SRC_IS_OPAQUE;
        }

        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            VKSurfaceData vkSrc = (VKSurfaceData)srcData;
            VKSurfaceData vkDst = (VKSurfaceData)dstData;
            int srctype = vkSrc.getType();
            boolean rtt;
            VKSurfaceData srcCtxData;
            if (srctype == VKSurfaceData.TEXTURE) {
                // the source is a regular texture object; we substitute
                // the destination surface for the purposes of making a
                // context current
                rtt = false;
                srcCtxData = vkDst;
            } else {
                // the source is a pbuffer, backbuffer, or render-to-texture
                // surface; we set rtt to true to differentiate this kind
                // of surface from a regular texture object
                rtt = true;
                if (srctype == AccelSurface.RT_TEXTURE) {
                    srcCtxData = vkDst;
                } else {
                    srcCtxData = vkSrc;
                }
            }

            VKContext.validateContext(srcCtxData, vkDst,
                    clip, comp, xform, null, null,
                    ctxflags);

            if (biop != null) {
                VKBufImgOps.enableBufImgOp(rq, vkSrc, srcImg, biop);
            }

            int packedParams = createPackedParams(true, xform != null, hint, 0 /*unused*/);
            enqueueBlit(rq, srcData, dstData,
                    packedParams,
                    sx1, sy1, sx2, sy2,
                    dx1, dy1, dx2, dy2);

            if (biop != null) {
                VKBufImgOps.disableBufImgOp(rq, biop);
            }
            if (rtt && vkDst.isOnScreen()) {
                // we only have to flush immediately when copying from a
                // (non-texture) surface to the screen; otherwise Swing apps
                // might appear unresponsive until the auto-flush completes
                rq.flushNow();
            }
        } finally {
            rq.unlock();
        }
    }
}

final class VKSurfaceToSurfaceBlit extends Blit {

    VKSurfaceToSurfaceBlit(CompositeType compositeType) {
        super(VKSurfaceData.VKSurface, compositeType, VKSurfaceData.VKSurface);
    }

    public void Blit(SurfaceData src, SurfaceData dst,
                     Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h)
    {
        VKBlitLoops.IsoBlit(src, dst,
                null, null,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h);
    }
}

final class VKSurfaceToSurfaceScale extends ScaledBlit {

    VKSurfaceToSurfaceScale(CompositeType compositeType) {
        super(VKSurfaceData.VKSurface, compositeType, VKSurfaceData.VKSurface);
    }

    public void Scale(SurfaceData src, SurfaceData dst,
                      Composite comp, Region clip,
                      int sx1, int sy1,
                      int sx2, int sy2,
                      double dx1, double dy1,
                      double dx2, double dy2)
    {
        VKBlitLoops.IsoBlit(src, dst,
                null, null,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2,
                dx1, dy1, dx2, dy2);
    }
}

final class VKSurfaceToSurfaceTransform extends TransformBlit {

    VKSurfaceToSurfaceTransform(CompositeType compositeType) {
        super(VKSurfaceData.VKSurface, compositeType, VKSurfaceData.VKSurface);
    }

    public void Transform(SurfaceData src, SurfaceData dst,
                          Composite comp, Region clip,
                          AffineTransform at, int hint,
                          int sx, int sy, int dx, int dy,
                          int w, int h)
    {
        VKBlitLoops.IsoBlit(src, dst,
                null, null,
                comp, clip, at, hint,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h);
    }
}

final class VKSurfaceToSwBlit extends Blit {

    private final VKFormat format;
    private final int transparency;
    private final SurfaceType bufferedSurfaceType;
    private WeakReference<SurfaceData> srcTmp;

    VKSurfaceToSwBlit(VKFormat format, int transparency) {
        // TODO Support for any composite via staged blit?
        //      This way we could do one intermediate blit instead of two.
        super(format.getSurfaceType(transparency), CompositeType.SrcNoEa, SurfaceType.Any);
        this.format = format;
        this.transparency = transparency;
        bufferedSurfaceType = format.getFormatModel(transparency).getSurfaceType();
    }

    private void stagedBlit(SurfaceData src, SurfaceData dst,
                            Composite comp, Region clip,
                            int sx, int sy, int dx, int dy,
                            int w, int h) {
        SurfaceData tmp = null;
        if (srcTmp != null) {
            // Use cached intermediate surface, if available.
            tmp = srcTmp.get();
            if (tmp != null) {
                Rectangle b = tmp.getBounds();
                if (b.width < w || b.height < h) tmp = null;
            }
        }
        if (tmp == null) {
            BufferedImage bi = format.createCompatibleImage(w, h, transparency);
            tmp = SurfaceData.getPrimarySurfaceData(bi);
            if (tmp.getSurfaceType() != bufferedSurfaceType) throw new RuntimeException("Incompatible surface type");
            srcTmp = null;
        }

        // Blit from Vulkan to intermediate SW image.
        Blit(src, tmp, null, null, sx, sy, 0, 0, w, h);

        // Copy intermediate SW to destination SW using complex clip.
        Blit performop = Blit.getFromCache(tmp.getSurfaceType(), CompositeType.SrcNoEa, dst.getSurfaceType());
        performop.Blit(tmp, dst, comp, clip, 0, 0, dx, dy, w, h);

        if (srcTmp == null) {
            // Cache the intermediate surface.
            srcTmp = new WeakReference<>(tmp);
        }
    }

    @Override
    public void Blit(SurfaceData src, SurfaceData dst,
                     Composite comp, Region clip,
                     int sx, int sy, int dx, int dy,
                     int w, int h) {
        if (clip != null) {
            clip = clip.getIntersectionXYWH(dx, dy, w, h);
            // At the end this method will flush the RenderQueue, we should exit
            // from it as soon as possible.
            if (clip.isEmpty()) {
                return;
            }
            sx += clip.getLoX() - dx;
            sy += clip.getLoY() - dy;
            dx = clip.getLoX();
            dy = clip.getLoY();
            w = clip.getWidth();
            h = clip.getHeight();
        }

        if ((clip != null && !clip.isRectangular()) || dst.getSurfaceType() != bufferedSurfaceType) {
            stagedBlit(src, dst, comp, clip, sx, sy, dx, dy, w, h);
            return;
        }

        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            // make sure the RenderQueue keeps a hard reference to the
            // destination (sysmem) SurfaceData to prevent it from being
            // disposed while the operation is processed on the QFT
            rq.addReference(dst);

            RenderBuffer buf = rq.getBuffer();
            VKContext.validateContext((VKSurfaceData)src);

            rq.ensureCapacityAndAlignment(48, 32);
            buf.putInt(SURFACE_TO_SW_BLIT);
            buf.putInt(sx).putInt(sy);
            buf.putInt(dx).putInt(dy);
            buf.putInt(w).putInt(h);
            buf.putInt(format.getValue());
            buf.putLong(src.getNativeOps());
            buf.putLong(dst.getNativeOps());

            // always flush immediately
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }
}

class VKSwToSurfaceBlit extends Blit {

    private int typeval;

    VKSwToSurfaceBlit(SurfaceType srcType, int typeval) {
        super(srcType,
                CompositeType.AnyAlpha,
                VKSurfaceData.VKSurface);
        this.typeval = typeval;
    }

    public void Blit(SurfaceData src, SurfaceData dst,
                     Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h)
    {
        VKBlitLoops.Blit(src, dst,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h,
                typeval, false);
    }
}

class VKSwToSurfaceScale extends ScaledBlit {

    private int typeval;

    VKSwToSurfaceScale(SurfaceType srcType, int typeval) {
        super(srcType,
                CompositeType.AnyAlpha,
                VKSurfaceData.VKSurface);
        this.typeval = typeval;
    }

    public void Scale(SurfaceData src, SurfaceData dst,
                      Composite comp, Region clip,
                      int sx1, int sy1,
                      int sx2, int sy2,
                      double dx1, double dy1,
                      double dx2, double dy2)
    {
        VKBlitLoops.Blit(src, dst,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2,
                dx1, dy1, dx2, dy2,
                typeval, false);
    }
}

class VKSwToSurfaceTransform extends TransformBlit {

    private int typeval;

    VKSwToSurfaceTransform(SurfaceType srcType, int typeval) {
        super(srcType,
                CompositeType.AnyAlpha,
                VKSurfaceData.VKSurface);
        this.typeval = typeval;
    }

    public void Transform(SurfaceData src, SurfaceData dst,
                          Composite comp, Region clip,
                          AffineTransform at, int hint,
                          int sx, int sy, int dx, int dy, int w, int h)
    {
        VKBlitLoops.Blit(src, dst,
                comp, clip, at, hint,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h,
                typeval, false);
    }
}

class VKSwToTextureBlit extends Blit {

    private int typeval;

    VKSwToTextureBlit(SurfaceType srcType, int typeval) {
        super(srcType,
                CompositeType.SrcNoEa,
                VKSurfaceData.VKTexture);
        this.typeval = typeval;
    }

    public void Blit(SurfaceData src, SurfaceData dst,
                     Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h)
    {
        VKBlitLoops.Blit(src, dst,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h,
                typeval, true);
    }
}

/**
 * This general Blit implementation converts any source surface to an
 * intermediate IntArgbPre surface, and then uses the more specific
 * IntArgbPre->VKSurface/Texture loop to get the intermediate
 * (premultiplied) surface down to Metal using simple blit.
 */
class VKGeneralBlit extends Blit {

    private final Blit performop;
    private WeakReference<SurfaceData> srcTmp;

    VKGeneralBlit(SurfaceType dstType,
                  CompositeType compType,
                  Blit performop)
    {
        super(SurfaceType.Any, compType, dstType);
        this.performop = performop;
    }

    public synchronized void Blit(SurfaceData src, SurfaceData dst,
                                  Composite comp, Region clip,
                                  int sx, int sy, int dx, int dy,
                                  int w, int h)
    {
        Blit convertsrc = Blit.getFromCache(src.getSurfaceType(),
                CompositeType.SrcNoEa,
                SurfaceType.IntArgbPre);

        SurfaceData cachedSrc = null;
        if (srcTmp != null) {
            // use cached intermediate surface, if available
            cachedSrc = srcTmp.get();
        }

        // convert source to IntArgbPre
        src = convertFrom(convertsrc, src, sx, sy, w, h,
                cachedSrc, BufferedImage.TYPE_INT_ARGB_PRE);

        // copy IntArgbPre intermediate surface to Metal surface
        performop.Blit(src, dst, comp, clip,
                0, 0, dx, dy, w, h);

        if (src != cachedSrc) {
            // cache the intermediate surface
            srcTmp = new WeakReference<>(src);
        }
    }
}

/**
 * This general TransformedBlit implementation converts any source surface to an
 * intermediate IntArgbPre surface, and then uses the more specific
 * IntArgbPre->VKSurface/Texture loop to get the intermediate
 * (premultiplied) surface down to Metal using simple transformBlit.
 */
final class VKGeneralTransformedBlit extends TransformBlit {

    private final TransformBlit performop;
    private WeakReference<SurfaceData> srcTmp;

    VKGeneralTransformedBlit(final TransformBlit performop) {
        super(SurfaceType.Any, CompositeType.AnyAlpha,
                VKSurfaceData.VKSurface);
        this.performop = performop;
    }

    @Override
    public synchronized void Transform(SurfaceData src, SurfaceData dst,
                                       Composite comp, Region clip,
                                       AffineTransform at, int hint, int srcx,
                                       int srcy, int dstx, int dsty, int width,
                                       int height){
        Blit convertsrc = Blit.getFromCache(src.getSurfaceType(),
                CompositeType.SrcNoEa,
                SurfaceType.IntArgbPre);
        // use cached intermediate surface, if available
        final SurfaceData cachedSrc = srcTmp != null ? srcTmp.get() : null;
        // convert source to IntArgbPre
        src = convertFrom(convertsrc, src, srcx, srcy, width, height, cachedSrc,
                BufferedImage.TYPE_INT_ARGB_PRE);

        // transform IntArgbPre intermediate surface to Metal surface
        performop.Transform(src, dst, comp, clip, at, hint, 0, 0, dstx, dsty,
                width, height);

        if (src != cachedSrc) {
            // cache the intermediate surface
            srcTmp = new WeakReference<>(src);
        }
    }
}

/**
 * This general VKAnyCompositeBlit implementation can convert any source/target
 * surface to an intermediate surface using convertsrc/convertdst loops, applies
 * necessary composite operation, and then uses convertresult loop to get the
 * intermediate surface down to Metal.
 */
final class VKAnyCompositeBlit extends Blit {

    private WeakReference<SurfaceData> dstTmp;
    private WeakReference<SurfaceData> srcTmp;
    private final Blit convertsrc;
    private final Blit convertdst;
    private final Blit convertresult;

    VKAnyCompositeBlit(SurfaceType srctype, Blit convertsrc, Blit convertdst,
                       Blit convertresult) {
        super(srctype, CompositeType.Any, VKSurfaceData.VKSurface);
        this.convertsrc = convertsrc;
        this.convertdst = convertdst;
        this.convertresult = convertresult;
    }

    public synchronized void Blit(SurfaceData src, SurfaceData dst,
                                  Composite comp, Region clip,
                                  int sx, int sy, int dx, int dy,
                                  int w, int h)
    {
        if (convertsrc != null) {
            SurfaceData cachedSrc = null;
            if (srcTmp != null) {
                // use cached intermediate surface, if available
                cachedSrc = srcTmp.get();
            }
            // convert source to IntArgbPre
            src = convertFrom(convertsrc, src, sx, sy, w, h, cachedSrc,
                    BufferedImage.TYPE_INT_ARGB_PRE);
            if (src != cachedSrc) {
                // cache the intermediate surface
                srcTmp = new WeakReference<>(src);
            }
        }

        SurfaceData cachedDst = null;

        if (dstTmp != null) {
            // use cached intermediate surface, if available
            cachedDst = dstTmp.get();
        }

        // convert destination to IntArgbPre
        SurfaceData dstBuffer = convertFrom(convertdst, dst, dx, dy, w, h,
                cachedDst, BufferedImage.TYPE_INT_ARGB_PRE);
        Region bufferClip =
                clip == null ? null : clip.getTranslatedRegion(-dx, -dy);

        Blit performop = Blit.getFromCache(src.getSurfaceType(),
                CompositeType.Any, dstBuffer.getSurfaceType());
        performop.Blit(src, dstBuffer, comp, bufferClip, sx, sy, 0, 0, w, h);

        if (dstBuffer != cachedDst) {
            // cache the intermediate surface
            dstTmp = new WeakReference<>(dstBuffer);
        }
        // now blit the buffer back to the destination
        convertresult.Blit(dstBuffer, dst, AlphaComposite.Src, clip, 0, 0, dx,
                dy, w, h);
    }
}
