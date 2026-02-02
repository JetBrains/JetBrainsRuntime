/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2023 JetBrains s.r.o.
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

package sun.awt.wl;

import sun.awt.image.OffScreenImage;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSurfaceSizeListener;
import sun.lwawt.LWComponentPeer;
import sun.lwawt.LWGraphicsConfig;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

public abstract class WLGraphicsConfig extends GraphicsConfiguration implements LWGraphicsConfig {
    private final WLGraphicsDevice device;
    private BufferCapabilities bufferCaps;

    protected WLGraphicsConfig(WLGraphicsDevice device) {
        this.device = device;
    }

    @Override
    public WLGraphicsDevice getDevice() {
        return device;
    }

    public Image createAcceleratedImage(Component target,
                                        int width, int height)
    {
        ColorModel model = getColorModel(Transparency.OPAQUE);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        return new OffScreenImage(target, model, raster, model.isAlphaPremultiplied());
    }

    @Override
    public AffineTransform getDefaultTransform() {
        double scale = device.getEffectiveScale();
        return AffineTransform.getScaleInstance(scale, scale);
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        double xScale = device.getResolutionX(this) / 72.0;
        double yScale = device.getResolutionY(this) / 72.0;
        return new AffineTransform(xScale, 0.0, 0.0, yScale, 0.0, 0.0);
    }

    @Override
    public Rectangle getBounds() {
        return device.getBounds();
    }

    public Rectangle getRealBounds() {
        return device.getRealBounds();
    }

    /**
     * Returns the preferred Wayland buffer scale for this display configuration.
     */
    public int getDisplayScale() {
        return device.getDisplayScale();
    }

    /**
     * Returns the effective scale, which can differ from the buffer scale
     * if overridden with the sun.java2d.uiScale system property.
     */
    public double getEffectiveScale() {
       return device.getEffectiveScale();
    }

    public abstract SurfaceType getSurfaceType();
    public abstract SurfaceData createSurfaceData(WLComponentPeer peer);
    public abstract SurfaceData createSurfaceData(WLSurfaceSizeListener sl, int width, int height);

    @Override
    public BufferCapabilities getBufferCapabilities() {
        if (bufferCaps == null) {
            bufferCaps = new WLDefaultBufferCapabilities();
        }
        return bufferCaps;
    }

    @Override
    public int getMaxTextureWidth() {
        // TODO
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxTextureHeight() {
        // TODO
        return Integer.MAX_VALUE;
    }

    @Override
    public void flush(LWComponentPeer<?, ?> peer) {
        // Not used by Wayland currently
    }

    @Override
    public String toString() {
        Rectangle bounds = getBounds();
        return String.format("%dx%d@(%d, %d) %dx scale", bounds.width, bounds.height, bounds.x, bounds.y, getDisplayScale());
    }

    private static class WLDefaultBufferCapabilities extends BufferCapabilities {
        WLDefaultBufferCapabilities() {
            super(new ImageCapabilities(false), new ImageCapabilities(false), FlipContents.UNDEFINED);
        }
    }
}
