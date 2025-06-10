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


import java.awt.BufferCapabilities;
import java.awt.ImageCapabilities;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;

import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.awt.wl.WLGraphicsDevice;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSurfaceSizeListener;
import sun.util.logging.PlatformLogger;

public final class WLVKGraphicsConfig extends WLGraphicsConfig
        implements VKGraphicsConfig {
    private static final PlatformLogger log =
            PlatformLogger.getLogger("sun.java2d.vulkan.WLVKGraphicsConfig");

    private final VKGraphicsConfig offscreenConfig;

    public WLVKGraphicsConfig(VKGraphicsConfig offscreenConfig, WLGraphicsDevice device,
                              int x, int y, int xLogical, int yLogical,
                              int width, int height, int widthLogical, int heightLogical,
                              int scale) {
        super(device, x, y, xLogical, yLogical, width, height, widthLogical, heightLogical, scale);
        this.offscreenConfig = offscreenConfig;
    }

    @Override
    public VKGraphicsConfig getOffscreenConfig() {
        return offscreenConfig;
    }

    @Override
    public double getScale() {
        return getEffectiveScale();
    }

    public static WLVKGraphicsConfig getConfig(VKGraphicsConfig offscreenConfig, WLGraphicsDevice device,
                                               int x, int y, int xLogical, int yLogical,
                                               int width, int height, int widthLogical, int heightLogical,
                                               int scale) {
        return new WLVKGraphicsConfig(offscreenConfig, device, x, y, xLogical, yLogical, width, height, widthLogical, heightLogical, scale);
    }

    @Override
    public BufferedImage createCompatibleImage(int width, int height) {
        return VKGraphicsConfig.super.createCompatibleImage(width, height);
    }
    @Override
    public BufferedImage createCompatibleImage(int width, int height, int transparency) {
        return VKGraphicsConfig.super.createCompatibleImage(width, height, transparency);
    }
    @Override
    public ColorModel getColorModel() {
        return VKGraphicsConfig.super.getColorModel();
    }
    @Override
    public ColorModel getColorModel(int transparency) {
        return VKGraphicsConfig.super.getColorModel(transparency);
    }
    @Override
    public BufferCapabilities getBufferCapabilities() {
        return VKGraphicsConfig.super.getBufferCapabilities();
    }
    @Override
    public ImageCapabilities getImageCapabilities() {
        return VKGraphicsConfig.super.getImageCapabilities();
    }
    @Override
    public boolean isTranslucencyCapable() {
        return VKGraphicsConfig.super.isTranslucencyCapable();
    }

    @Override
    public String toString() {
        return "WLVKGraphicsConfig[" + descriptorString() + ", " + getDevice().getIDstring() + "]";
    }

    @Override
    public SurfaceData createSurfaceData(WLComponentPeer peer) {
        return new WLVKWindowSurfaceData(peer);
    }

    @Override
    public SurfaceData createSurfaceData(WLSurfaceSizeListener sl, int width, int height) {
        return new WLVKWindowSurfaceData(sl, width, height, this);
    }

    public SurfaceType getSurfaceType() {
        return SurfaceType.IntArgb;
    }
}
