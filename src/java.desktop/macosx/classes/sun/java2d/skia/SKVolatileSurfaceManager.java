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
import sun.java2d.SurfaceData;
import sun.java2d.metal.MTLGraphicsConfig;
import sun.java2d.pipe.hw.AccelSurface;

import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.ColorModel;

/** Creates Skia-backed surfaces for VolatileImages; mirrors {@code MTLVolatileSurfaceManager}. */
public final class SKVolatileSurfaceManager extends VolatileSurfaceManager {

    private final boolean accelerationEnabled;

    public SKVolatileSurfaceManager(SunVolatileImage vImg, Object context) {
        super(vImg, context);
        accelerationEnabled = vImg.getTransparency() != Transparency.BITMASK;
    }

    @Override
    protected boolean isAccelerationEnabled() {
        return accelerationEnabled;
    }

    @Override
    protected SurfaceData initAcceleratedSurface() {
        try {
            MTLGraphicsConfig gc = (MTLGraphicsConfig) vImg.getGraphicsConfig();
            ColorModel cm = gc.getColorModel(vImg.getTransparency());
            int type = vImg.getForcedAccelSurfaceType();
            if (type == AccelSurface.UNDEFINED) {
                type = AccelSurface.RT_TEXTURE;
            }
            return SKSurfaceData.createData(gc, vImg.getWidth(), vImg.getHeight(), cm, vImg, type);
        } catch (NullPointerException | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Override
    protected boolean isConfigValid(GraphicsConfiguration gc) {
        return (gc == null) || (gc == vImg.getGraphicsConfig());
    }

    @Override
    public void initContents() {
        if (vImg.getForcedAccelSurfaceType() != AccelSurface.TEXTURE) {
            super.initContents();
        }
    }
}
