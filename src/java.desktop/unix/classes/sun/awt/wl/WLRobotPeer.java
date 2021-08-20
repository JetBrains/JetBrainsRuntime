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

package sun.awt.wl;

import java.awt.*;
import java.awt.peer.RobotPeer;

public class WLRobotPeer implements RobotPeer {
    private final WLGraphicsConfig wgc;

    static {
        initIDs();
    }

    public WLRobotPeer(WLGraphicsDevice gd) {
        wgc = (WLGraphicsConfig) gd.getDefaultConfiguration();
    }

    @Override
    public void mouseMove(int x, int y) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.mouseMove()");
    }

    @Override
    public void mousePress(int buttons) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.mousePress()");
    }

    @Override
    public void mouseRelease(int buttons) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.mouseRelease()");
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.mouseWheel()");
    }

    @Override
    public void keyPress(int keycode) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.keyPress()");
    }

    @Override
    public void keyRelease(int keycode) {
        throw new UnsupportedOperationException("Not implemented: WLRobotPeer.keyRelease()");
    }

    @Override
    public int getRGBPixel(int x, int y) {
        checkExtensionPresent();

        // The native implementation allows for just one such request at a time
        synchronized(WLRobotPeer.class) {
            return getRGBPixelImpl(x, y);
        }
    }

    @Override
    public int [] getRGBPixels(Rectangle bounds) {
        checkExtensionPresent();

        // The native implementation allows for just one such request at a time
        synchronized(WLRobotPeer.class) {
            return getRGBPixelsImpl(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    /**
     * Retrieves the location in absolute coordinates of the Wayland
     * surface pointed to by the argument.
     * Throws UnsupportedOperationException if the Wayland extension
     * that implements this wasn't loaded on the server side.
     *
     * @param wlSurfacePtr a pointer to struct wl_surface
     * @return location of the surface in absolute coordinates
     */
    static Point getLocationOfWLSurface(long wlSurfacePtr) {
        checkExtensionPresent();

        // The native implementation allows for just one such request at a time
        synchronized(WLRobotPeer.class) {
            return getLocationOfWLSurfaceImpl(wlSurfacePtr);
        }
    }

    /**
     * Change the screen location of the given Wayland surface to the given
     * absolute coordinates.
     * Does nothing if the Wayland extension that supports this functionality
     * was not loaded by the server.
     *
     * @param wlSurfacePtr a pointer to struct wl_surface
     * @param x the absolute x-coordinate
     * @param y the absolute y-coordinate
     */
    static void setLocationOfWLSurface(long wlSurfacePtr, int x, int y) {
        if (isRobotExtensionPresent) {
            setLocationOfWLSurfaceImpl(wlSurfacePtr, x, y);
        }
    }

    private static void checkExtensionPresent() {
        if (!isRobotExtensionPresent) {
            throw new UnsupportedOperationException("WLRobotPeer: wakefield extension not present in Wayland instance");
        }
    }

    private static final boolean  isRobotExtensionPresent = isRobotExtensionPresentImpl();

    private static native void    initIDs();

    private static native boolean isRobotExtensionPresentImpl();
    private static native int     getRGBPixelImpl(int x, int y);
    private static native int[]   getRGBPixelsImpl(int x, int y, int width, int height);
    private static native Point   getLocationOfWLSurfaceImpl(long wlSurfacePtr);
    private static native void    setLocationOfWLSurfaceImpl(long wlSurfacePtr, int x, int y);
}
