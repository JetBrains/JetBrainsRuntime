/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

package sun.java2d.wl;

import java.awt.GraphicsConfiguration;
import java.awt.ImageCapabilities;
import java.awt.geom.AffineTransform;
import java.awt.image.VolatileImage;
import java.util.Objects;

import sun.awt.image.SunVolatileImage;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.SurfaceData;

public class WLVolatileSurfaceManager extends VolatileSurfaceManager {
    public WLVolatileSurfaceManager(SunVolatileImage vImg, Object context) {
        super(vImg, context);
    }

    protected boolean isAccelerationEnabled() {
        return false;
    }

    @Override
    protected SurfaceData initAcceleratedSurface() {
        throw new UnsupportedOperationException("accelerated surface not supported");
    }

    @Override
    public ImageCapabilities getCapabilities(GraphicsConfiguration gc) {
        // neither accelerated nor volatile
        return new ImageCapabilities(false);
    }

    @Override
    public int validate(GraphicsConfiguration gc) {
        AffineTransform newTx = gc.getDefaultTransform();
        if (!Objects.equals(atCurrent, newTx)) {
            // May need a different size on another display
            return VolatileImage.IMAGE_INCOMPATIBLE;
        }
        return super.validate(gc);
    }
}
