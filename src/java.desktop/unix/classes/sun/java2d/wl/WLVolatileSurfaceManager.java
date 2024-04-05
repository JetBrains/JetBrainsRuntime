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

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.ImageCapabilities;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import sun.awt.image.SunVolatileImage;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.SurfaceData;

public class WLVolatileSurfaceManager extends VolatileSurfaceManager implements PropertyChangeListener {
    private static final String SCALE_PROPERTY_NAME = "graphicsContextScaleTransform";

    public WLVolatileSurfaceManager(SunVolatileImage vImg, Object context) {
        super(vImg, context);
        Component component = vImg.getComponent();
        if (component != null) {
            component.addPropertyChangeListener(SCALE_PROPERTY_NAME, this);
        }
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
    public void propertyChange(PropertyChangeEvent evt) {
        assert SCALE_PROPERTY_NAME.equals(evt.getPropertyName());

        displayChanged();
    }
}
