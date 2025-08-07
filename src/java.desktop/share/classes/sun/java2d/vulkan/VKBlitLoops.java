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
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.lang.annotation.Native;
import java.lang.ref.WeakReference;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sun.java2d.pipe.BufferedOpCodes.BLIT;
import static sun.java2d.pipe.BufferedOpCodes.SURFACE_TO_SW_BLIT;
import static java.awt.Transparency.OPAQUE;
import static java.awt.Transparency.TRANSLUCENT;

final class VKBlitLoops {

    static void register() {
        List<GraphicsPrimitive> primitives = new ArrayList<>();
        // Sw->Surface, any src/dst type, any alpha/xor composite.
        primitives.addAll(VKSwToSurfaceBlit.INSTANCE.primitives);
        // Surface->Surface, any src/dst type, any alpha/xor composite.
        primitives.addAll(VKSurfaceToSurfaceBlit.INSTANCE.primitives);
        // Surface->Sw, any dst type, only plain copy (SrcNoEa).
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
            VKSurfaceData srcCtxData;
            if (srctype == VKSurfaceData.TEXTURE) {
                // the source is a regular texture object; we substitute
                // the destination surface for the purposes of making a
                // context current
                srcCtxData = vkDst;
            } else {
                // the source is a pbuffer, backbuffer, or render-to-texture surface
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
        } finally {
            rq.unlock();
        }
    }
}

@FunctionalInterface
interface VKStageSurfaceFactory {
    Image create(int width, int height);
}

/**
 * Cached temporary surface, used as an intermediate blit destination.
 */
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
            Image image = factory.create(width, height);
            currentSD = SurfaceData.getPrimarySurfaceData(image);
            cachedSD = new WeakReference<>(currentSD);
        }
        return currentSD;
    }

    @Override
    public void close() {
        currentSD = null;
    }
}

/**
 * Unified implementation for Blit, ScaledBlit, TransformBlit,
 * allowing multiple combinations of src/dst and composite types.
 */
abstract class VKMultiplexedBlit {

    final List<GraphicsPrimitive> primitives = new ArrayList<>();

    abstract void blit(SurfaceData src, SurfaceData dst,
                       Composite comp, Region clip,
                       AffineTransform xform, int hint,
                       int sx1, int sy1,
                       int sx2, int sy2,
                       double dx1, double dy1,
                       double dx2, double dy2,
                       int w, int h);

    void registerBlits(SurfaceType srctype, CompositeType comptype, SurfaceType dsttype) {
        registerBlit(srctype, comptype, dsttype);
        registerScaledBlit(srctype, comptype, dsttype);
        registerTransformBlit(srctype, comptype, dsttype);
    }

    void registerBlit(SurfaceType srctype, CompositeType comptype, SurfaceType dsttype) {
        primitives.add(new Blit(srctype, comptype, dsttype) {
            @Override
            public void Blit(SurfaceData src, SurfaceData dst, Composite comp, Region clip,
                             int sx, int sy, int dx, int dy, int w, int h) {
                blit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                     sx, sy, sx+w, sy+h, dx, dy, dx+w, dy+h, w, h);
            }
        });
    }

    void registerScaledBlit(SurfaceType srctype, CompositeType comptype, SurfaceType dsttype) {
        primitives.add(new ScaledBlit(srctype, comptype, dsttype) {
            @Override
            public void Scale(SurfaceData src, SurfaceData dst,
                              Composite comp, Region clip,
                              int sx1, int sy1, int sx2, int sy2,
                              double dx1, double dy1, double dx2, double dy2) {
                blit(src, dst, comp, clip, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR,
                     sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2, sx2 - sx1, sy2 - sy1);
            }
        });
    }

    void registerTransformBlit(SurfaceType srctype, CompositeType comptype, SurfaceType dsttype) {
        primitives.add(new TransformBlit(srctype, comptype, dsttype) {
            @Override
            public void Transform(SurfaceData src, SurfaceData dst,
                                  Composite comp, Region clip,
                                  AffineTransform at, int hint,
                                  int sx, int sy, int dx, int dy,
                                  int w, int h) {
                blit(src, dst, comp, clip, at, hint,
                     sx, sy, sx+w, sy+h, dx, dy, dx+w, dy+h, w, h);
            }
        });
    }
}

final class VKSurfaceToSurfaceBlit extends VKMultiplexedBlit {

    static final VKSurfaceToSurfaceBlit INSTANCE = new VKSurfaceToSurfaceBlit();

    // We need to use stage Vulkan surfaces for blit of the surface into itself.
    // The cache maps offscreen graphics configs (GPU + format) to stage surfaces.
    private final Map<VKGraphicsConfig, VKStageSurface> stageCache = new HashMap<>();

    private VKSurfaceToSurfaceBlit() {
        // Any Vulkan src type: we can sample any Vulkan surface format.
        // Any Vulkan dst type: we don't care about dst format as long as we can render into it.
        // Any alpha/xor composite is supported.
        registerBlits(VKSurfaceData.VKSurface, CompositeType.AnyAlpha, VKSurfaceData.VKSurface);
        registerBlits(VKSurfaceData.VKSurface, CompositeType.Xor,      VKSurfaceData.VKSurface);
    }

    private VKStageSurface getStage(VKSurfaceData src) {
        // If accelerated surface data is disabled, create the compatible buffered stage image instead.
        return stageCache.computeIfAbsent(src.getGraphicsConfig().getOffscreenConfig(),
                gc -> new VKStageSurface(!VKEnv.isSurfaceDataAccelerated() ? gc::createCompatibleImage :
                        (w, h) -> gc.createCompatibleVolatileImage(
                                w, h, gc.isTranslucencyCapable() ? TRANSLUCENT : OPAQUE, AccelSurface.RT_TEXTURE)));
    }

    @Override
    void blit(SurfaceData src, SurfaceData dst,
              Composite comp, Region clip, AffineTransform xform, int hint,
              int sx1, int sy1, int sx2, int sy2,
              double dx1, double dy1, double dx2, double dy2,
              int w, int h) {
        if (src == dst) { // Blit into itself cannot be done directly, need to use stage surface.
            try (VKStageSurface stageSurface = getStage((VKSurfaceData) src)) {
                SurfaceData stage = stageSurface.acquire(w, h);
                if (VKEnv.isSurfaceDataAccelerated()) {
                    // Blit into the intermediate image.
                    blit(src, stage, null, null, null, AffineTransformOp.TYPE_NEAREST_NEIGHBOR, sx1, sy1, sx2, sy2, 0, 0, w, h, w, h);
                    // Blit back from the intermediate image.
                    blit(stage, dst, comp, clip, xform, hint, 0, 0, w, h, dx1, dy1, dx2, dy2, w, h);
                } else { // Accelerated surface data disabled - blit via buffered stage image.
                    // Blit into the intermediate image.
                    Blit srcop = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, stage.getSurfaceType());
                    srcop.Blit(src, stage, null, null, sx1, sy1, 0, 0, w, h);
                    // Blit back from the intermediate image.
                    VKSwToSurfaceBlit.INSTANCE.blit(stage, dst, comp, clip, xform, hint, 0, 0, w, h, dx1, dy1, dx2, dy2, w, h);
                }
            }
            return;
        }
        VKBlitLoops.IsoBlit(src, dst,
                null, null,
                comp, clip, xform, hint,
                sx1, sy1, sx2, sy2,
                dx1, dy1, dx2, dy2);
    }
}

final class VKSurfaceToSwBlit extends Blit {

    private final VKStageSurface stageSurface;
    private final SurfaceType bufferedSurfaceType;

    VKSurfaceToSwBlit(VKFormat format, int transparency) {
        // TODO Support for any composite via staged blit?
        //      This way we could do one intermediate blit instead of two.
        // We can only copy image data from the Surface into the matching Sw format (see bufferedSurfaceType).
        // Specific Vulkan src type: we register specific blit loop for each format + transparency variant.
        // Any dst type: we use a stage surface with compatible format in case of non-matching format or complex clip.
        // Only plain copy (SrcNoEa composite) is supported.
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

final class VKSwToSurfaceBlit extends VKMultiplexedBlit {

    static final VKSwToSurfaceBlit INSTANCE = new VKSwToSurfaceBlit();

    // Don't use pre-multiplied alpha to preserve color values whenever possible.
    private final VKStageSurface stage4Byte = new VKStageSurface((w, h) -> new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB));

    private VKSwToSurfaceBlit() {
        // Any src type: we either blit directly, if we can sample a given source format (see encodeSrcType),
        // or via a stage Sw surface, taking the closest format to represent source colors (see getStage).
        // Any Vulkan dst type: we don't care about dst format as long as we can render into it.
        // Any alpha/xor composite is supported.
        registerBlits(SurfaceType.Any, CompositeType.AnyAlpha, VKSurfaceData.VKSurface);
        registerBlits(SurfaceType.Any, CompositeType.Xor,      VKSurfaceData.VKSurface);
    }

    private VKStageSurface getStage(SurfaceData src, VKSurfaceData dst) {
        // We assume that 4 byte sampled format is always supported.
        return stage4Byte;
    }

    @Native private static final int SRCTYPE_PRE_MULTIPLIED_ALPHA_BIT = 1 << 15;
    @Native private static final int SRCTYPE_BITS = 2;
    @Native private static final int SRCTYPE_MASK = (1 << SRCTYPE_BITS) - 1;
    @Native private static final int SRCTYPE_4BYTE = 0;
    @Native private static final int SRCTYPE_3BYTE = 1;
    @Native private static final int SRCTYPE_565   = 2;
    @Native private static final int SRCTYPE_555   = 3;

    private static int getDCMComponentIndex(int mask) {
        int index;
        switch (mask) {
            case 0x000000ff -> index = 0;
            case 0x0000ff00 -> index = 1;
            case 0x00ff0000 -> index = 2;
            case 0xff000000 -> index = 3;
            default -> { return -1; }
        }
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) index = 3 - index;
        return index;
    }

    private static int encode4Byte(int r, int g, int b, int a, boolean alphaPre) {
        return SRCTYPE_4BYTE |
                (r <<  SRCTYPE_BITS     ) |
                (g << (SRCTYPE_BITS + 2)) |
                (b << (SRCTYPE_BITS + 4)) |
                (a << (SRCTYPE_BITS + 6)) |
                (alphaPre ? SRCTYPE_PRE_MULTIPLIED_ALPHA_BIT : 0);
    }

    private static int encode3Byte(int r, int g, int b) {
        return SRCTYPE_3BYTE |
                (r <<  SRCTYPE_BITS     ) |
                (g << (SRCTYPE_BITS + 2)) |
                (b << (SRCTYPE_BITS + 4));
    }

    private static boolean hasCap(VKSurfaceData dst, int cap) {
        return dst.getGraphicsConfig().getGPU().hasCap(cap);
    }

    /**
     * Encode src surface type for native blit.
     * Return -1 if the surface type is not supported.
     * All stage surfaces from getStage() must always be supported.
     * See decodeSrcType() in VKBlitLoops.c
     */
    private int encodeSrcType(SurfaceData src, VKSurfaceData dst) {
        if (src.getNativeOps() == 0) return -1; // No native raster info - needs a staged blit.
        // We assume that the 4-byte sampled format is always supported.
        if (src.getColorModel() instanceof DirectColorModel dcm) {
            if (dcm.getTransferType() == DataBuffer.TYPE_INT) {
                // Int-packed format. Can be directly mapped to 4-byte format with arbitrary component order.
                int r, g, b, a;
                if ((r = getDCMComponentIndex(dcm.getRedMask()))   == -1 ||
                        (g = getDCMComponentIndex(dcm.getGreenMask())) == -1 ||
                        (b = getDCMComponentIndex(dcm.getBlueMask()))  == -1) return -1;
                if (!dcm.hasAlpha()) a = r; // Special case, a = r means no alpha.
                else if ((a = getDCMComponentIndex(dcm.getAlphaMask())) == -1) return -1;
                return encode4Byte(r, g, b, a, dcm.isAlphaPremultiplied());
            } else if (dcm.getTransferType() == DataBuffer.TYPE_SHORT || dcm.getTransferType() == DataBuffer.TYPE_USHORT) {
                // Short-packed format, we support few standard formats.
                if (dcm.getRedMask() == 0xf800 && dcm.getGreenMask() == 0x07E0 && dcm.getBlueMask() == 0x001F) {
                    if (hasCap(dst, VKGPU.CAP_SAMPLED_565_BIT)) return SRCTYPE_565;
                } else if (dcm.getRedMask() == 0x7C00 && dcm.getGreenMask() == 0x03E0 && dcm.getBlueMask() == 0x001F) {
                    if (hasCap(dst, VKGPU.CAP_SAMPLED_555_BIT)) return SRCTYPE_555;
                }
            }
        } else if (src.getColorModel() instanceof ComponentColorModel &&
                   src.getColorModel().getColorSpace().isCS_sRGB() &&
                   src.getRaster(0, 0, 0, 0).getSampleModel() instanceof PixelInterleavedSampleModel sm &&
                   sm.getTransferType() == DataBuffer.TYPE_BYTE) {
            // Byte-interleaved format. We support 3 and 4-byte formats with arbitrary component order.
            int stride = sm.getPixelStride();
            if (stride == 4 || (stride == 3 && hasCap(dst, VKGPU.CAP_SAMPLED_3BYTE_BIT))) {
                int[] bands = sm.getBandOffsets();
                if (bands.length >= 3 &&
                    bands[0] >= 0 && bands[0] < stride &&
                    bands[1] >= 0 && bands[1] < stride &&
                    bands[2] >= 0 && bands[2] < stride) {
                    if (stride == 3) {
                        return encode3Byte(bands[0], bands[1], bands[2]);
                    } else if (bands.length == 4 && bands[3] >= 0 && bands[3] < stride) {
                        return encode4Byte(bands[0], bands[1], bands[2], bands[3], src.getColorModel().isAlphaPremultiplied());
                    } else if (bands.length == 3) {
                        return encode4Byte(bands[0], bands[1], bands[2], bands[0], false); // Special case, a = r means no alpha.
                    }
                }
            }
        }
        return -1;
    }

    @Override
    void blit(SurfaceData src, SurfaceData dst,
              Composite comp, Region clip, AffineTransform xform, int hint,
              int sx1, int sy1, int sx2, int sy2,
              double dx1, double dy1, double dx2, double dy2,
              int w, int h) {
        // Clip to destination bounds.
        // This may help to reduce the amount of memory to be copied.
        if (xform == null) {
            double cx1 = 0, cy1 = 0, cx2 = ((VKSurfaceData) dst).width, cy2 = ((VKSurfaceData) dst).height;
            if (clip != null) {
                if (clip.getLoX() > cx1) cx1 = clip.getLoX();
                if (clip.getLoY() > cy1) cy1 = clip.getLoY();
                if (clip.getHiX() < cx2) cx2 = clip.getHiX();
                if (clip.getHiY() < cy2) cy2 = clip.getHiY();
            }
            if (cx1 > dx1) {
                double rx = (dx2 - dx1) / (sx2 - sx1);
                int ix = (int) ((cx1 - dx1) / rx);
                sx1 += ix; dx1 += rx * ix;
            }
            if (cx2 < dx2) {
                double rx = (dx2 - dx1) / (sx2 - sx1);
                int ix = (int) ((cx2 - dx2) / rx);
                sx2 += ix; dx2 += rx * ix;
            }
            if (cy1 > dy1) {
                double ry = (dy2 - dy1) / (sy2 - sy1);
                int iy = (int) ((cy1 - dy1) / ry);
                sy1 += iy; dy1 += ry * iy;
            }
            if (cy2 < dy2) {
                double ry = (dy2 - dy1) / (sy2 - sy1);
                int iy = (int) ((cy2 - dy2) / ry);
                sy2 += iy; dy2 += ry * iy;
            }
            w = sx2 - sx1;
            h = sy2 - sy1;
            if (w <= 0 || h <= 0) return;
        }

        int srcType = encodeSrcType(src, (VKSurfaceData) dst);
        if (srcType == -1) {
            try (VKStageSurface stageSurface = getStage(src, (VKSurfaceData) dst)) {
                SurfaceData stage = stageSurface.acquire(w, h);
                // Blit from src to intermediate SW image.
                Blit op = Blit.getFromCache(src.getSurfaceType(), CompositeType.SrcNoEa, stage.getSurfaceType());
                op.Blit(src, stage, AlphaComposite.Src, null, sx1, sy1, 0, 0, w, h);
                // Copy intermediate SW to Vulkan surface.
                blit(stage, dst, comp, clip, xform, hint, 0, 0, w, h, dx1, dy1, dx2, dy2, w, h);
            }
            return;
        }
        VKBlitLoops.Blit(src, dst,
                         comp, clip, xform, hint,
                         sx1, sy1, sx2, sy2,
                         dx1, dy1, dx2, dy2,
                         srcType, false);
    }
}
