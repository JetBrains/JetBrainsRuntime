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

import sun.awt.image.SunVolatileImage;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.hw.AccelSurface;

import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.VolatileImage;

public class VKVolatileSurfaceManager extends VolatileSurfaceManager {

    private final boolean accelerationEnabled;

    public VKVolatileSurfaceManager(SunVolatileImage vImg, Object context) {
        super(vImg, context);

        /*
         * We will attempt to accelerate this image only
         * if the image is not bitmask
         */
        int transparency = vImg.getTransparency();

        accelerationEnabled = VKEnv.isSurfaceDataAccelerated() &&
                transparency != Transparency.BITMASK;
    }

    protected boolean isAccelerationEnabled() {
        return accelerationEnabled;
    }

    /**
     * Create a SurfaceData object (or init the backbuffer
     * of an existing window if this is a double buffered GraphicsConfig)
     */
    protected SurfaceData initAcceleratedSurface() {
        try {
            VKGraphicsConfig gc = (VKGraphicsConfig) vImg.getGraphicsConfig();
            int type = vImg.getForcedAccelSurfaceType();
            // if acceleration type is forced (type != UNDEFINED) then
            // use the forced type, otherwise choose RT_TEXTURE
            if (type == AccelSurface.UNDEFINED) {
                type = AccelSurface.RT_TEXTURE;
            }
            VKOffScreenSurfaceData sd = new VKOffScreenSurfaceData(vImg, gc.getFormat(), vImg.getTransparency(), type,
                                                                   vImg.getWidth(), vImg.getHeight());
            sd.revalidate(gc);
            sd.configure();
            return sd;
        } catch (NullPointerException | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Override
    public int validate(GraphicsConfiguration gc) {
        if (gc != null && sdAccel != null && isAccelerationEnabled() && isConfigValid(gc)) {
            VKSurfaceData vksd = (VKSurfaceData) sdAccel;
            switch (vksd.revalidate((VKGraphicsConfig) gc)) {
                case VolatileImage.IMAGE_INCOMPATIBLE:
                    return VolatileImage.IMAGE_INCOMPATIBLE;
                case VolatileImage.IMAGE_RESTORED:
                    vksd.setSurfaceLost(true);
                    vksd.configure();
            }
        }
        return super.validate(gc);
    }

    @Override
    protected boolean isConfigValid(GraphicsConfiguration gc) {
        // We consider configs with the same format compatible across Vulkan devices.
        return gc == null || vImg.getGraphicsConfig() == null ||
                ((VKGraphicsConfig) gc).getFormat() == ((VKGraphicsConfig) vImg.getGraphicsConfig()).getFormat();
    }

    @Override
    public void initContents() {
        if (vImg.getForcedAccelSurfaceType() != AccelSurface.TEXTURE) {
            super.initContents();
        }
    }
}
