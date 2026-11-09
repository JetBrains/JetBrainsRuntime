/*
 * Copyright 2026 JetBrains s.r.o.
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
package sun.java2d.skia;

import sun.awt.image.SunVolatileImage;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.InvalidPipeException;
import sun.java2d.SurfaceData;

import sun.java2d.pipe.hw.AccelSurface;

import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.ColorModel;

/** Creates Skia-backed surfaces for VolatileImages; mirrors {@code MTLVolatileSurfaceManager}. */
final class SKVolatileSurfaceManager extends VolatileSurfaceManager {

    private final boolean accelerationEnabled;

    SKVolatileSurfaceManager(SunVolatileImage volatileImage, Object context) {
        super(volatileImage, context);
        accelerationEnabled = volatileImage.getTransparency() != Transparency.BITMASK;
    }

    @Override
    protected boolean isAccelerationEnabled() {
        return accelerationEnabled;
    }

    /**
     * A Skia surface on the image's configuration; null (software) when the image is on
     * a configuration that is not Skia's any more: a display change moves a component's
     * image to the component's configuration, which may be a GDI one (a window on a
     * screen Skia does not take, a device recovery that ends without Skia).
     */
    @Override
    protected SurfaceData initAcceleratedSurface() {
        if (!(vImg.getGraphicsConfig() instanceof SKGraphicsConfig graphicsConfig)) {
            return null;
        }
        try {
            ColorModel colorModel = graphicsConfig.getColorModel(vImg.getTransparency());
            int type = vImg.getForcedAccelSurfaceType();
            if (type == AccelSurface.UNDEFINED) {
                type = AccelSurface.RT_TEXTURE;
            }
            return graphicsConfig.createSKSurfaceData(vImg.getWidth(), vImg.getHeight(), colorModel, vImg, type);
        } catch (NullPointerException | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * A lost surface (a lost device on Windows) is replaced by a new one on
     * its configuration; when that fails the image falls back to software.
     */
    @Override
    protected void restoreAcceleratedSurface() {
        SurfaceData surfaceData = initAcceleratedSurface();
        if (surfaceData == null) {
            throw new InvalidPipeException("could not restore the Skia surface");
        }
        sdAccel = surfaceData;
    }

    @Override
    protected boolean isConfigValid(GraphicsConfiguration graphicsConfig) {
        return (graphicsConfig == null) || (graphicsConfig == vImg.getGraphicsConfig());
    }

    @Override
    public void initContents() {
        if (vImg.getForcedAccelSurfaceType() != AccelSurface.TEXTURE) {
            super.initContents();
        }
    }
}
