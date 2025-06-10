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

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

import sun.awt.image.OffScreenImage;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSurfaceSizeListener;

public abstract class WLGraphicsConfig extends GraphicsConfiguration {
    private final WLGraphicsDevice device;
    private final int x;
    private final int y;
    private final int xLogical; // logical (scaled) horizontal location; optional, could be zero
    private final int yLogical; // logical (scaled) vertical location; optional, could be zero
    private final int width;
    private final int height;
    private final int widthLogical; // logical (scaled) width; optional, could be zero
    private final int heightLogical;// logical (scaled) height; optional, could be zero
    private final int displayScale; // as reported by Wayland
    private final double effectiveScale; // as enforced by Java

    protected WLGraphicsConfig(WLGraphicsDevice device, int x, int y, int xLogical, int yLogical,
                               int width, int height, int widthLogical, int heightLogical,
                               int displayScale) {
        this.device = device;
        this.x = x;
        this.y = y;
        this.xLogical = xLogical;
        this.yLogical = yLogical;
        this.width = width;
        this.height = height;
        this.widthLogical = widthLogical;
        this.heightLogical = heightLogical;
        this.displayScale = displayScale;
        this.effectiveScale = WLGraphicsEnvironment.effectiveScaleFrom(displayScale);
    }

    boolean differsFrom(int width, int height, int scale) {
        return width != this.width || height != this.height || scale != this.displayScale;
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
        double scale = effectiveScale;
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
        return (widthLogical > 0 && heightLogical > 0)
                ? new Rectangle(xLogical, yLogical, widthLogical, heightLogical)
                : new Rectangle(x, y, width, height);
    }

    public Rectangle getRealBounds() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Returns the preferred Wayland buffer scale for this display configuration.
     */
    public int getDisplayScale() {
        return displayScale;
    }

    /**
     * Returns the effective scale, which can differ from the buffer scale
     * if overridden with the sun.java2d.uiScale system property.
     */
    public double getEffectiveScale() {
       return effectiveScale;
    }

    public abstract SurfaceType getSurfaceType();
    public abstract SurfaceData createSurfaceData(WLComponentPeer peer);
    public abstract SurfaceData createSurfaceData(WLSurfaceSizeListener sl, int width, int height);

    @Override
    public String toString() {
        return String.format("%dx%d@(%d, %d) %dx scale", width, height, x, y, displayScale);
    }
}
