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

import sun.awt.SunToolkit;
import sun.java2d.SurfaceData;
import sun.java2d.wl.WLPixelGrabberExt;

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
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            mouseMoveImpl(x, y);
        }
    }

    @Override
    public void mousePress(int buttons) {
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            sendMouseButtonImpl(buttons, true);
        }
    }

    @Override
    public void mouseRelease(int buttons) {
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            sendMouseButtonImpl(buttons, false);
        }
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            mouseWheelImpl(wheelAmt);
        }
    }

    @Override
    public void keyPress(int keycode) {
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            sendJavaKeyImpl(keycode, true);
        }
    }

    @Override
    public void keyRelease(int keycode) {
        checkExtensionPresent();

        synchronized (WLRobotPeer.class) {
            sendJavaKeyImpl(keycode, false);
        }
    }

    @Override
    public int getRGBPixel(int x, int y) {
        if (isRobotExtensionPresent) {
            // The native implementation allows for just one such request at a time
            synchronized (WLRobotPeer.class) {
                return getRGBPixelImpl(x, y);
            }
        } else {
            // Can get pixels from the singular window's surface data,
            // not necessarily the true value that the user observes.
            return getRGBPixelOfSingularWindow(x, y);
        }
    }

    @Override
    public int [] getRGBPixels(Rectangle bounds) {
        if (isRobotExtensionPresent) {
            // The native implementation allows for just one such request at a time
            synchronized (WLRobotPeer.class) {
                return getRGBPixelsImpl(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        } else {
            // Can get pixels from the singular window's surface data,
            // not necessarily the true value that the user observes.
            return getRGBPixelsOfSingularWindow(bounds);
        }
    }

    private int getRGBPixelOfSingularWindow(int x, int y) {
        WLComponentPeer peer = WLToolkit.getSingularWindowPeer();
        Point loc = peer.convertPontFromDeviceSpace(x, y);
        WLToolkit.awtLock();
        try {
            checkPeerForPixelGrab(peer);
            return SurfaceData.convertTo(WLPixelGrabberExt.class, peer.surfaceData).getRGBPixelAt(loc.x, loc.y);
        } finally {
            WLToolkit.awtUnlock();
        }
    }

    private int [] getRGBPixelsOfSingularWindow(Rectangle bounds) {
        WLComponentPeer peer = WLToolkit.getSingularWindowPeer();
        Point loc = peer.convertPontFromDeviceSpace(bounds.x, bounds.y);
        Rectangle adjustedBounds = new Rectangle(loc, bounds.getSize());
        WLToolkit.awtLock();
        try {
            checkPeerForPixelGrab(peer);
            return SurfaceData.convertTo(WLPixelGrabberExt.class, peer.surfaceData).getRGBPixelsAt(adjustedBounds);
        } finally {
            WLToolkit.awtUnlock();
        }
    }

    private static void checkPeerForPixelGrab(WLComponentPeer peer) {
        if (!peer.isVisible()) {
            throw new UnsupportedOperationException("The window has no backing buffer to read pixels from");
        }
        if (! (peer.surfaceData instanceof WLPixelGrabberExt)) {
            throw new UnsupportedOperationException("WLPixelGrabberExt is required to read pixels from a Wayland surface");
        }
    }

    /**
     * Retrieves the location in absolute coordinates of the Wayland
     * surface pointed to by the argument.
     * Throws UnsupportedOperationException if the Wayland extension
     * that implements this wasn't loaded on the server side.
     *
     * @return location of the surface in absolute coordinates
     */
    static Point getLocationOfWLSurface(WLSurface wlSurface) {
        checkExtensionPresent();

        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";

        final long wlSurfacePtr = wlSurface.getWlSurfacePtr();
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
     * @param wlSurface WLSurface
     * @param x the absolute x-coordinate
     * @param y the absolute y-coordinate
     */
    static void setLocationOfWLSurface(WLSurface wlSurface, int x, int y) {
        if (isRobotExtensionPresent) {
            long wlSurfacePtr = wlSurface.getWlSurfacePtr();
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
    private static native void    sendJavaKeyImpl(int javaKeyCode, boolean pressed);
    private static native void    mouseMoveImpl(int x, int y);
    private static native void    sendMouseButtonImpl(int buttons, boolean pressed);
    private static native void    mouseWheelImpl(int amount);

    public static native void setXKBLayout(String layout, String variant, String options);
}
