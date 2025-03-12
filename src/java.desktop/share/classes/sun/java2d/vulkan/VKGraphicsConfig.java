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

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.Surface;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.hw.AccelGraphicsConfig;
import sun.java2d.pipe.hw.AccelTypedVolatileImage;
import sun.java2d.pipe.hw.ContextCapabilities;

import java.awt.BufferCapabilities;
import java.awt.GraphicsConfiguration;
import java.awt.ImageCapabilities;

import static java.awt.Transparency.OPAQUE;
import static java.awt.Transparency.TRANSLUCENT;
import static sun.java2d.pipe.hw.AccelSurface.RT_TEXTURE;
import static sun.java2d.pipe.hw.AccelSurface.TEXTURE;

/**
 * Base type for Vulkan graphics config.
 * Despite it being an interface, it contains a (preferred) default implementation
 * for most of the methods, including base methods of GraphicsConfiguration class.
 */
public interface VKGraphicsConfig extends AccelGraphicsConfig,
        SurfaceManager.ProxiedGraphicsConfig, SurfaceManager.Factory {

    @Override
    default VolatileSurfaceManager createVolatileManager(SunVolatileImage image,
                                                         Object context) {
        return new VKVolatileSurfaceManager(image, context);
    }

    VKGraphicsConfig getOffscreenConfig();

    default VKGPU getGPU() {
        return getOffscreenConfig().getGPU();
    }

    default VKFormat getFormat() {
        return getOffscreenConfig().getFormat();
    }

    @Override
    default SurfaceManager.ProxyCache getSurfaceDataProxyCache() {
        return getGPU().getSurfaceDataProxyCache();
    }

    @Override
    default BufferedContext getContext() {
        return VKContext.INSTANCE;
    }

    /**
     * Returns true if the provided capability bit is present for this config.
     * See VKContext.java for a list of supported capabilities.
     */
    default boolean isCapPresent(int cap) { // TODO refactor capability checks.
        return ((getContextCapabilities().getCaps() & cap) != 0);
    }

    @Override
    default ContextCapabilities getContextCapabilities() {
        return VKContext.VKContextCaps.CONTEXT_CAPS;
    }

    @Override
    default VolatileImage createCompatibleVolatileImage(int width, int height, int transparency, int type) {
        if (type != RT_TEXTURE && type != TEXTURE) return null;
        if (transparency != OPAQUE && (transparency != TRANSLUCENT || !isTranslucencyCapable())) return null;
        SunVolatileImage vi =
                new AccelTypedVolatileImage((GraphicsConfiguration) this, width, height, transparency, type);
        Surface sd = vi.getDestSurface();
        if (!(sd instanceof VKSurfaceData vsd) || vsd.getType() != type) {
            vi.flush();
            vi = null;
        }
        return vi;
    }

    default String descriptorString() {
        return getFormat().name() + ", " + getGPU();
    }

    // Default implementation of GraphicsConfiguration methods.
    // Those need to be explicitly overridden by subclasses using VKGraphicsConfig.super.

    default BufferedImage createCompatibleImage(int width, int height) {
        return createCompatibleImage(width, height, isTranslucencyCapable() ? TRANSLUCENT : OPAQUE);
    }

    default BufferedImage createCompatibleImage(int width, int height, int transparency) {
        return getFormat().createCompatibleImage(width, height, transparency);
    }

    default ColorModel getColorModel() {
        return getColorModel(isTranslucencyCapable() ? TRANSLUCENT : OPAQUE);
    }

    default ColorModel getColorModel(int transparency) {
        return getFormat().getFormatModel(transparency).getColorModel();
    }

    default BufferCapabilities getBufferCapabilities() {
        return VKContext.VKContextCaps.BUFFER_CAPS;
    }

    default ImageCapabilities getImageCapabilities() {
        return VKContext.VKContextCaps.IMAGE_CAPS;
    }

    default boolean isTranslucencyCapable() {
        return getFormat().isTranslucencyCapable();
    }
}
