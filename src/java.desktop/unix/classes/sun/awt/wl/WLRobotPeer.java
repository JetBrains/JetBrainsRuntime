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
import sun.awt.screencast.XdgDesktopPortalRobot;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceData;
import sun.java2d.wl.WLPixelGrabberExt;

import java.awt.*;
import java.awt.peer.RobotPeer;

public class WLRobotPeer implements RobotPeer {
    private final WLGraphicsConfig wgc;

    private static final boolean useRemoteDesktopRobot =
            Boolean.parseBoolean(System.getProperty("sun.awt.wl.UseRemoteDesktopRobot", "false"));

    public WLRobotPeer(WLGraphicsDevice gd) {
        wgc = (WLGraphicsConfig) gd.getDefaultConfiguration();
    }

    @Override
    public void mouseMove(int x, int y) {
        if (useRemoteDesktopRobotForInput()) {
            Point p = SunGraphicsEnvironment.toDeviceSpaceAbs(x, y);
            XdgDesktopPortalRobot.mouseMove(p.x, p.y);
        }
    }

    @Override
    public void mousePress(int buttons) {
        if (useRemoteDesktopRobotForInput()) {
            XdgDesktopPortalRobot.mouseButton(true, buttons);
        }
    }

    @Override
    public void mouseRelease(int buttons) {
        if (useRemoteDesktopRobotForInput()) {
            XdgDesktopPortalRobot.mouseButton(false, buttons);
        }
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        if (useRemoteDesktopRobotForInput()) {
            XdgDesktopPortalRobot.mouseWheel(wheelAmt);
        }
    }

    @Override
    public void keyPress(int keycode) {
        if (useRemoteDesktopRobotForInput()) {
            XdgDesktopPortalRobot.key(true, keycode);
        }
    }

    @Override
    public void keyRelease(int keycode) {
        if (useRemoteDesktopRobotForInput()) {
            XdgDesktopPortalRobot.key(false, keycode);
        }
    }

    @Override
    public int getRGBPixel(int x, int y) {
        if (useRemoteDesktopRobotForCapture()) {
            return XdgDesktopPortalRobot.getRGBPixel(x, y);
        }

        // Can get pixels from the singular window's surface data,
        // not necessarily the true value that the user observes.
        return getRGBPixelOfSingularWindow(x, y);
    }

    @Override
    public int[] getRGBPixels(Rectangle bounds) {
        if (useRemoteDesktopRobotForCapture()) {
            return XdgDesktopPortalRobot.getRGBPixels(bounds);
        }

        // Can get pixels from the singular window's surface data,
        // not necessarily the true value that the user observes.
        return getRGBPixelsOfSingularWindow(bounds);
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

    private int[] getRGBPixelsOfSingularWindow(Rectangle bounds) {
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
        if (!(peer.surfaceData instanceof WLPixelGrabberExt)) {
            throw new UnsupportedOperationException("WLPixelGrabberExt is required to read pixels from a Wayland surface");
        }
    }

    @Override
    public boolean useAbsoluteCoordinates() {
        return useRemoteDesktopRobotForCapture();
    }

    private static boolean useRemoteDesktopRobotForInput() {
        return useRemoteDesktopRobot && XdgDesktopPortalRobot.useForInput();
    }

    private static boolean useRemoteDesktopRobotForCapture() {
        return useRemoteDesktopRobot && XdgDesktopPortalRobot.useForCapture();
    }
}
