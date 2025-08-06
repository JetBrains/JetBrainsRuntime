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

import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.BufferedContext;

/**
 * SurfaceData object representing an off-screen buffer
 */
public class VKOffScreenSurfaceData extends VKSurfaceData {
    private final Image offscreenImage;
    private native void initOps(int width, int height);

    public VKOffScreenSurfaceData(VKGraphicsConfig gc, Image image, ColorModel cm,
                                  int type, int width, int height)
    {
        super(gc, cm, type, width, height);
        offscreenImage = image;
        initOps(width, height);
    }

    @Override
    public SurfaceData getReplacement() {
        return restoreContents(offscreenImage);
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return null;
    }

    @Override
    public long getNativeResource(int resType) {
        return 0;
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    /**
     * Returns destination Image associated with this SurfaceData.
     */
    @Override
    public Object getDestination() {
        return offscreenImage;
    }

    @Override
    public BufferedContext getContext() {
        return getGraphicsConfig().getContext();
    }


    @Override
    public boolean isOnScreen() {
        return false;
    }
}
