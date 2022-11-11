/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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


import static sun.java2d.pipe.hw.AccelSurface.RT_TEXTURE;
import static sun.java2d.pipe.hw.AccelSurface.TEXTURE;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_MULTITEXTURE;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_PS20;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_PS30;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_RT_TEXTURE_ALPHA;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_RT_TEXTURE_OPAQUE;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_TEXNONPOW2;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_TEXNONSQUARE;

import java.awt.BufferCapabilities;
import java.awt.ImageCapabilities;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.wl.WLGraphicsConfig;
import sun.java2d.Disposer;
import sun.java2d.DisposerRecord;
import sun.java2d.Surface;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.hw.AccelGraphicsConfig;
import sun.java2d.pipe.hw.AccelSurface;
import sun.java2d.pipe.hw.AccelTypedVolatileImage;
import sun.java2d.pipe.hw.ContextCapabilities;
import sun.util.logging.PlatformLogger;

public final class VKGraphicsConfig extends WLGraphicsConfig
        implements AccelGraphicsConfig, SurfaceManager.ProxiedGraphicsConfig
{
    private static final PlatformLogger log =
            PlatformLogger.getLogger("sun.java2d.vulkan.VKGraphicsConfig");

    private static boolean vkAvailable;
    private static ImageCapabilities imageCaps = new VKImageCaps();
    private final int maxTextureSize;

    private BufferCapabilities bufferCaps;
    private long pConfigInfo;
    private ContextCapabilities vkCaps;
    private final Object disposerReferent = new Object();
    private final VKContext context;

    private static native boolean isVulkanAvailable();
    private static native long getMTLConfigInfo(int displayID);

    /**
     * Returns maximum texture size supported by Vulkan. Must be
     * called under VKRQ lock.
     */
    private static native int nativeGetMaxTextureSize();

    static {
        vkAvailable = isVulkanAvailable();
    }

    private VKGraphicsConfig(VKGraphicsDevice device,
                             long configInfo, int maxTextureSize,
                             ContextCapabilities vkCaps) {
        super(device);

        this.pConfigInfo = configInfo;
        this.vkCaps = vkCaps;
        this.maxTextureSize = maxTextureSize;
        context = new VKContext(VKRenderQueue.getInstance());
        // add a record to the Disposer so that we destroy the native
        // VKGraphicsConfigInfo data when this object goes away
        Disposer.addRecord(disposerReferent,
                new VKGCDisposerRecord(pConfigInfo));
    }

    @Override
    public Object getProxyKey() {
        return this;
    }

    public SurfaceData createManagedSurface(int w, int h, int transparency) {
        log.info("Not implemented: VKGraphicsConfig.createManagedSurface(int w, int h, " +
                 "int transparency)");
        return null;
    }

    public static VKGraphicsConfig getConfig(VKGraphicsDevice device,
                                              int displayID)
    {
        if (!vkAvailable) {
            return null;
        }

        long cfginfo = 0;
        int textureSize = 0;
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            cfginfo = getMTLConfigInfo(displayID);
            if (cfginfo != 0L) {
                textureSize = nativeGetMaxTextureSize();
                // TODO : This clamping code is same as in OpenGL.
                // Whether we need such clamping or not in case of Metal
                // will be pursued under 8260644
                textureSize = textureSize <= 16384 ? textureSize / 2 : 8192;
                VKContext.setScratchSurface(cfginfo);
            }
        } finally {
            rq.unlock();
        }
        if (cfginfo == 0) {
            return null;
        }

        ContextCapabilities caps = new VKContext.VKContextCaps(
                CAPS_PS30 | CAPS_PS20 |
                        CAPS_RT_TEXTURE_ALPHA | CAPS_RT_TEXTURE_OPAQUE |
                        CAPS_MULTITEXTURE | CAPS_TEXNONPOW2 | CAPS_TEXNONSQUARE,
                null);
        return new VKGraphicsConfig(device, cfginfo, textureSize, caps);
    }

    /**
     * Returns true if the provided capability bit is present for this config.
     * See VKContext.java for a list of supported capabilities.
     */
    public boolean isCapPresent(int cap) {
        return ((vkCaps.getCaps() & cap) != 0);
    }

    public long getNativeConfigInfo() {
        return pConfigInfo;
    }

    /**
     * {@inheritDoc}
     *
     * @see sun.java2d.pipe.hw.BufferedContextProvider#getContext
     */
    @Override
    public VKContext getContext() {
        return context;
    }

    @Override
    public BufferedImage createCompatibleImage(int width, int height) {
        ColorModel model = new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        WritableRaster
                raster = model.createCompatibleWritableRaster(width, height);
        return new BufferedImage(model, raster, model.isAlphaPremultiplied(),
                null);
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        switch (transparency) {
            case Transparency.OPAQUE:
                return new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
            case Transparency.BITMASK:
                return new DirectColorModel(25, 0xff0000, 0xff00, 0xff, 0x1000000);
            case Transparency.TRANSLUCENT:
                ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                return new DirectColorModel(cs, 32,
                        0xff0000, 0xff00, 0xff, 0xff000000,
                        true, DataBuffer.TYPE_INT);
            default:
                return null;
        }
    }

    public boolean isDoubleBuffered() {
        return true;
    }

    private static class VKGCDisposerRecord implements DisposerRecord {
        private long pCfgInfo;
        public VKGCDisposerRecord(long pCfgInfo) {
            this.pCfgInfo = pCfgInfo;
        }
        public void dispose() {
            if (pCfgInfo != 0) {
                VKRenderQueue.disposeGraphicsConfig(pCfgInfo);
                pCfgInfo = 0;
            }
        }
    }

    @Override
    public String toString() {
        return ("MTLGraphicsConfig[" + getDevice().getIDstring() + "]");
    }


    private static class VKBufferCaps extends BufferCapabilities {
        public VKBufferCaps(boolean dblBuf) {
            super(imageCaps, imageCaps,
                    dblBuf ? FlipContents.UNDEFINED : null);
        }
    }

    @Override
    public BufferCapabilities getBufferCapabilities() {
        if (bufferCaps == null) {
            bufferCaps = new VKBufferCaps(isDoubleBuffered());
        }
        return bufferCaps;
    }

    private static class VKImageCaps extends ImageCapabilities {
        private VKImageCaps() {
            super(true);
        }
        public boolean isTrueVolatile() {
            return true;
        }
    }

    @Override
    public ImageCapabilities getImageCapabilities() {
        return imageCaps;
    }

    @Override
    public VolatileImage createCompatibleVolatileImage(int width, int height,
                                                       int transparency,
                                                       int type) {
        if ((type != RT_TEXTURE && type != TEXTURE) ||
            transparency == Transparency.BITMASK) {
            return null;
        }

        SunVolatileImage vi = new AccelTypedVolatileImage(this, width, height,
                transparency, type);
        Surface sd = vi.getDestSurface();
        if (!(sd instanceof AccelSurface) ||
                ((AccelSurface)sd).getType() != type)
        {
            vi.flush();
            vi = null;
        }

        return vi;
    }

    /**
     * {@inheritDoc}
     *
     * @see AccelGraphicsConfig#getContextCapabilities
     */
    @Override
    public ContextCapabilities getContextCapabilities() {
        return vkCaps;
    }
}
