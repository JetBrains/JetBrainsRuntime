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

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.VolatileImage;

import sun.java2d.SurfaceData;

/**
 * SurfaceData object representing an off-screen buffer
 */
public class VKOffScreenSurfaceData extends VKSurfaceData {

    private final Image offscreenImage;
    private final int userWidth, userHeight; // In logical units.

    private native void initOps(int format);

    public VKOffScreenSurfaceData(Image image, VKFormat format, int transparency, int type, int width, int height) {
        super(format, transparency, type);
        this.userWidth = width;
        this.userHeight = height;
        offscreenImage = image;
        initOps(format.getValue(transparency));
    }

    @Override
    public SurfaceData getReplacement() {
        return restoreContents(offscreenImage);
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
    protected int revalidate(VKGraphicsConfig gc) {
        int result = super.revalidate(gc);
        if (result != VolatileImage.IMAGE_INCOMPATIBLE) {
            scale = gc.getScale();
            width = (int) Math.ceil(scale * userWidth);
            height = (int) Math.ceil(scale * userHeight);
        }
        return result;
    }

    @Override
    public boolean isOnScreen() {
        return false;
    }
}
