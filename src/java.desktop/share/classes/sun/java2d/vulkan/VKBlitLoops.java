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
import java.util.List;

import static sun.java2d.pipe.BufferedOpCodes.BLIT;
import static sun.java2d.pipe.BufferedOpCodes.SURFACE_TO_SW_BLIT;
import static java.awt.Transparency.OPAQUE;
import static java.awt.Transparency.TRANSLUCENT;

final class VKBlitLoops {

    static void register() {
        List<GraphicsPrimitive> primitives = new ArrayList<>();
        VKSwToSurfaceBlitContext blitContext = new VKSwToSurfaceBlitContext();
        for (CompositeType compositeType : new CompositeType[] { CompositeType.AnyAlpha, CompositeType.Xor }) {
            // Sw->Surface
            primitives.add(new VKSwToSurfaceBlit(compositeType, blitContext));
            primitives.add(new VKSwToSurfaceScale(compositeType, blitContext));
            primitives.add(new VKSwToSurfaceTransform(compositeType, blitContext));
            // Surface->Surface
            primitives.add(new VKSurfaceToSurfaceBlit(compositeType));
            primitives.add(new VKSurfaceToSurfaceScale(compositeType));
            primitives.add(new VKSurfaceToSurfaceTransform(compositeType));
        }
        // Surface->Sw & Any composite per format
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

@FunctionalInterface
interface VKStageSurfaceFactory {
    BufferedImage create(int width, int height);
}

final class VKStageSurface implements AutoCloseable {

    private final VKStageSurfaceFactory factory;
    private WeakReference<SurfaceData> cachedSD;
    private SurfaceData currentSD;

    VKStageSurface(VKStageSurfaceFactory factory) {
        this.factory = factory;
    }

    SurfaceData acquire(int width, int height) {
        if(currentSD != null) throw new IllegalStateException("Already acquired, recursive blit?");
        if (cachedSD != null) {
            currentSD = cachedSD.get();
            if (currentSD != null) {
                Rectangle b = currentSD.getBounds();
                if (b.width < width || b.height < height) currentSD = null;
            } else cachedSD = null;
        }
        if (currentSD == null) {
            BufferedImage bi = factory.create(width, height);
            currentSD = SurfaceData.getPrimarySurfaceData(bi);
            cachedSD = new WeakReference<>(currentSD);
        }
        return currentSD;
    }

    @Override
    public void close() {
        currentSD = null;
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

    private final VKStageSurface stageSurface;
    private final SurfaceType bufferedSurfaceType;

    VKSurfaceToSwBlit(VKFormat format, int transparency) {
        // TODO Support for any composite via staged blit?
        //      This way we could do one intermediate blit instead of two.
        super(format.getSurfaceType(transparency), CompositeType.SrcNoEa, SurfaceType.Any);
        stageSurface = new VKStageSurface((w, h) -> format.createCompatibleImage(w, h, transparency));
        bufferedSurfaceType = format.getFormatModel(transparency).getSurfaceType();
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
            try (stageSurface) {
                SurfaceData stage = stageSurface.acquire(w, h);
                // Blit from Vulkan to intermediate SW image.
                Blit(src, stage, null, null, sx, sy, 0, 0, w, h);
                // Copy intermediate SW to destination SW using complex clip.
                Blit op = Blit.getFromCache(stage.getSurfaceType(), CompositeType.SrcNoEa, dst.getSurfaceType());
                op.Blit(stage, dst, comp, clip, 0, 0, dx, dy, w, h);
            }
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
            buf.putInt(0 /*unused*/);
            buf.putLong(src.getNativeOps());
            buf.putLong(dst.getNativeOps());

            // always flush immediately
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }
}

final class VKSwToSurfaceBlitContext {

    // TODO switch to TYPE_INT_ARGB when blit shader is fixed to handle straight alpha?
    // Don't use pre-multiplied alpha to preserve color values whenever possible.
    private final VKStageSurface stage4Byte = new VKStageSurface((w, h) -> new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE));

    VKStageSurface getStage(SurfaceData src, VKSurfaceData dst) {
        // We assume that 4 byte sampled format is always supported.
        return stage4Byte;
    }

    /**
     * Encode src surface type for native blit.
     * Return -1 if the surface type is not supported.
     * All stage surfaces from getStage() must always be supported.
     */
    int encodeSrcType(SurfaceData src) {
        // TODO Needs to be implemented.
        //      Currently, all blits are performed via a staged surface.
        if (src.getSurfaceType() == SurfaceType.IntArgbPre) return 0;
        return -1;
    }
}

class VKSwToSurfaceBlit extends Blit {

    private final VKSwToSurfaceBlitContext blitContext;

    VKSwToSurfaceBlit(CompositeType compositeType, VKSwToSurfaceBlitContext blitContext) {
        super(SurfaceType.Any, compositeType, VKSurfaceData.VKSurface);
        this.blitContext = blitContext;
    }

    public void Blit(SurfaceData src, SurfaceData dst,
                     Composite comp, Region clip,
                     int sx, int sy, int dx, int dy, int w, int h) {
        int srcType = blitContext.encodeSrcType(src);
        if (srcType == -1) {
            try (VKStageSurface stageSurface = blitContext.getStage(src, (VKSurfaceData) dst)) {
                SurfaceData stage = stageSurface.acquire(w, h);
                // Blit from src to intermediate SW image.
                Blit op = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, stage.getSurfaceType());
                op.Blit(src, stage, AlphaComposite.Src, null, sx, sy, 0, 0, w, h);
                // Copy intermediate SW to Vulkan surface.
                Blit(stage, dst, comp, clip, 0, 0, dx, dy, w, h);
            }
            return;
        }
        VKBlitLoops.Blit(src, dst,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h,
                srcType, false);
    }
}

class VKSwToSurfaceScale extends ScaledBlit {

    private final VKSwToSurfaceBlitContext blitContext;

    VKSwToSurfaceScale(CompositeType compositeType, VKSwToSurfaceBlitContext blitContext) {
        super(SurfaceType.Any, compositeType, VKSurfaceData.VKSurface);
        this.blitContext = blitContext;
    }

    public void Scale(SurfaceData src, SurfaceData dst,
                      Composite comp, Region clip,
                      int sx1, int sy1,
                      int sx2, int sy2,
                      double dx1, double dy1,
                      double dx2, double dy2) {
        int srcType = blitContext.encodeSrcType(src);
        if (srcType == -1) {
            try (VKStageSurface stageSurface = blitContext.getStage(src, (VKSurfaceData) dst)) {
                int w = sx2-sx1, h = sy2-sy1;
                SurfaceData stage = stageSurface.acquire(w, h);
                // Blit from src to intermediate SW image.
                Blit op = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, stage.getSurfaceType());
                op.Blit(src, stage, AlphaComposite.Src, null, sx1, sy1, 0, 0, w, h);
                // Copy intermediate SW to Vulkan surface.
                Scale(stage, dst, comp, clip, 0, 0, w, h, dx1, dy1, dx2, dy2);
            }
            return;
        }
        VKBlitLoops.Blit(src, dst,
                comp, clip, null,
                AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                sx1, sy1, sx2, sy2,
                dx1, dy1, dx2, dy2,
                srcType, false);
    }
}

class VKSwToSurfaceTransform extends TransformBlit {

    private final VKSwToSurfaceBlitContext blitContext;

    VKSwToSurfaceTransform(CompositeType compositeType, VKSwToSurfaceBlitContext blitContext) {
        super(SurfaceType.Any, compositeType, VKSurfaceData.VKSurface);
        this.blitContext = blitContext;
    }

    public void Transform(SurfaceData src, SurfaceData dst,
                          Composite comp, Region clip,
                          AffineTransform at, int hint,
                          int sx, int sy, int dx, int dy, int w, int h) {
        int srcType = blitContext.encodeSrcType(src);
        if (srcType == -1) {
            try (VKStageSurface stageSurface = blitContext.getStage(src, (VKSurfaceData) dst)) {
                SurfaceData stage = stageSurface.acquire(w, h);
                // Blit from src to intermediate SW image.
                Blit op = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, stage.getSurfaceType());
                op.Blit(src, stage, AlphaComposite.Src, null, sx, sy, 0, 0, w, h);
                // Copy intermediate SW to Vulkan surface.
                Transform(stage, dst, comp, clip, at, hint, 0, 0, dx, dy, w, h);
            }
            return;
        }
        VKBlitLoops.Blit(src, dst,
                comp, clip, at, hint,
                sx, sy, sx+w, sy+h,
                dx, dy, dx+w, dy+h,
                srcType, false);
    }
}
