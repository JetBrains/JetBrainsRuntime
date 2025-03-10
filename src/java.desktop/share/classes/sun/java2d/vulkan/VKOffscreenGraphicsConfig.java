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

import java.awt.BufferCapabilities;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.ImageCapabilities;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;

public class VKOffscreenGraphicsConfig extends GraphicsConfiguration implements VKGraphicsConfig {
    private final VKOffsecreenGraphicsDevice graphicsDevice = new VKOffsecreenGraphicsDevice(this);
    private final VKGPU gpu;

    VKOffscreenGraphicsConfig(VKGPU gpu) {
        this.gpu = gpu;
    }

    @Override
    public GraphicsDevice getDevice() {
        return graphicsDevice;
    }

    @Override
    public VKGraphicsConfig getOffscreenConfig() {
        return this;
    }

    @Override
    public VKGPU getGPU() {
        return gpu;
    }

    @Override
    public AffineTransform getDefaultTransform() {
        return new AffineTransform();
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        return new AffineTransform();
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
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
}
