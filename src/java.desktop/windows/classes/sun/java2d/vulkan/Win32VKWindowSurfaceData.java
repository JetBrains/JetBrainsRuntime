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

import java.awt.Rectangle;

public class Win32VKWindowSurfaceData extends VKSurfaceData {

    private final WComponentPeer peer;

    public Win32VKWindowSurfaceData(WComponentPeer peer) {
        super(((VKGraphicsConfig) peer.getGraphicsConfiguration()).getFormat(),
              peer.getColorModel().getTransparency(), WINDOW);
        this.peer = peer;
        this.gc = (VKGraphicsConfig) peer.getGraphicsConfiguration();

        long hwnd = peer.getHWnd();
        updateBoundsFromNativeData(hwnd);
        initOps(getFormat().getValue(getTransparency()));
        assignWindow(hwnd);
        configure();
    }

    public void revalidate() {
        long hwnd = peer.getHWnd();
        updateBoundsFromNativeData(hwnd);
        revalidate((VKGraphicsConfig) peer.getGraphicsConfiguration());
        configure();
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
        return new Rectangle(width, height);
    }

    @Override
    public Object getDestination() {
        return peer.getTarget();
    }

    private native void initOps(int format);

    private native void assignWindow(long hwnd);

    private static native long getClientAreaSizePackedIntoLong(long hwnd);

    private void updateBoundsFromNativeData(long hwnd) {
        long packedWidthHeight = getClientAreaSizePackedIntoLong(hwnd);
        this.width = (int) (packedWidthHeight >>> 32);
        this.height = (int) packedWidthHeight;
    }
}
