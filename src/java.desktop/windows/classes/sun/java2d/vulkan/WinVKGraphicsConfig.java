/*
 * Copyright 2025 JetBrains s.r.o.
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

import sun.awt.Win32GraphicsConfig;
import sun.awt.Win32GraphicsDevice;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.VolatileSurfaceManager;
import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.hw.ContextCapabilities;

import java.awt.image.VolatileImage;

public final class WinVKGraphicsConfig extends Win32GraphicsConfig implements VKGraphicsConfig {

    private final VKGraphicsConfig offscreenConfig;

    @SuppressWarnings("deprecation")
    public WinVKGraphicsConfig(VKGraphicsConfig offscreenConfig, Win32GraphicsDevice device, int visualnum) {
        super(device, visualnum);
        this.offscreenConfig = offscreenConfig;
    }

    // VKGraphicsConfig

    @Override
    public VolatileSurfaceManager createVolatileManager(SunVolatileImage image, Object context) {
        return VKGraphicsConfig.super.createVolatileManager(image, context);
    }

    @Override
    public VKGraphicsConfig getOffscreenConfig() {
        return offscreenConfig;
    }

    @Override
    public VKGPU getGPU() {
        return VKGraphicsConfig.super.getGPU();
    }

    @Override
    public VKFormat getFormat() {
        return VKGraphicsConfig.super.getFormat();
    }

    @Override
    public double getScale() {
        return getDevice().getDefaultScaleX();
    }

    @Override
    public BufferedContext getContext() {
        return VKGraphicsConfig.super.getContext();
    }

    @Override
    public boolean isCapPresent(int cap) {
        return VKGraphicsConfig.super.isCapPresent(cap);
    }

    @Override
    public ContextCapabilities getContextCapabilities() {
        return VKGraphicsConfig.super.getContextCapabilities();
    }

    @Override
    public VolatileImage createCompatibleVolatileImage(int width, int height, int transparency, int type) {
        return VKGraphicsConfig.super.createCompatibleVolatileImage(width, height, transparency, type);
    }

    @Override
    public String descriptorString() {
        return VKGraphicsConfig.super.descriptorString();
    }

    @Override
    public String toString() {
        return "WLVKGraphicsConfig[" + descriptorString() + ", " + getDevice().getIDstring() + "]";
    }

    @Override
    public SurfaceData createSurfaceData(WComponentPeer peer, int numBackBuffers) {
        return new WinVKWindowSurfaceData(peer);
    }
}
