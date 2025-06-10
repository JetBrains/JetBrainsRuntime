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

import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;

import java.awt.*;

public class WinVKWindowSurfaceData extends VKSurfaceData {

    private WComponentPeer peer;

    public WinVKWindowSurfaceData(WComponentPeer peer) {
        VKGraphicsConfig gc = (VKGraphicsConfig) peer.getGraphicsConfiguration();
        super(gc.getFormat(), peer.getColorModel().getTransparency(), WINDOW);
        this.peer = peer;
        final int backgroundRGB = Color.GREEN.getRGB();
        initOps(gc.getFormat().getValue(getTransparency()), backgroundRGB);
    }

    @Override
    public SurfaceData getReplacement() {
        return null;
    }

    @Override
    public long getNativeResource(int resType) {
        return 0;
    }

    @Override
    public Rectangle getBounds() {
        Rectangle bounds = peer.getBounds();
        bounds.x = bounds.y = 0;
        return bounds;
    }

    @Override
    public Object getDestination() {
        return peer.getTarget();
    }

    private native void initOps(int format, int backgroundRGB);
}
