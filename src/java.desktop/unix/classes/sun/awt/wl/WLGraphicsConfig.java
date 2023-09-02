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

public abstract class WLGraphicsConfig extends GraphicsConfiguration {
    private final WLGraphicsDevice device;
    private final int width;
    private final int height;
    private final int scale;

    protected WLGraphicsConfig(WLGraphicsDevice device, int width, int height, int scale) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    boolean differsFrom(int width, int height, int scale) {
        return width != this.width || height != this.height || scale != this.scale;
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
        double scale = getScale();
        return AffineTransform.getScaleInstance(scale, scale);
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        // TODO: may not be able to implement this fully, but we can try
        // obtaining physical width/height from wl_output.geometry event.
        // Those may be 0, of course.
        return getDefaultTransform();
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    public int getScale() {
        return scale;
    }

    public abstract SurfaceType getSurfaceType();
    public abstract SurfaceData createSurfaceData(WLComponentPeer peer);

    @Override
    public String toString() {
        return String.format("%dx%d %dx scale", width, height, scale);
    }
}
