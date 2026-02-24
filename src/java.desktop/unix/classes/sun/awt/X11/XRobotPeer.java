/*
 * Copyright (c) 2003, 2025, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.X11;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.peer.RobotPeer;

import sun.awt.AWTAccessor;
import sun.awt.SunToolkit;
import sun.awt.UNIXToolkit;
import sun.awt.X11GraphicsConfig;
import sun.awt.X11GraphicsDevice;
import sun.awt.X11GraphicsEnvironment;
import sun.awt.screencast.XdgDesktopPortalRobot;

final class XRobotPeer implements RobotPeer {

    private static final boolean tryGtk;

    static {
        loadNativeLibraries();

        tryGtk = Boolean.parseBoolean(
                            System.getProperty("awt.robot.gtk", "true")
                 );
    }

    private static volatile boolean useGtk;
    private final X11GraphicsConfig xgc;

    XRobotPeer(X11GraphicsDevice gd) {
        xgc = (X11GraphicsConfig) gd.getDefaultConfiguration();
        SunToolkit tk = (SunToolkit)Toolkit.getDefaultToolkit();
        setup(tk.getNumberOfButtons(),
                AWTAccessor.getInputEventAccessor().getButtonDownMasks());

        boolean isGtkSupported = false;
        if (tryGtk) {
            if (tk instanceof UNIXToolkit && ((UNIXToolkit) tk).loadGTK()) {
                isGtkSupported = true;
            }
        }

        useGtk = (tryGtk && isGtkSupported);
    }

    @Override
    public void mouseMove(int x, int y) {
        Point p = ((X11GraphicsEnvironment) X11GraphicsEnvironment.getLocalGraphicsEnvironment())
                .scaleUp(xgc.getDevice(), x, y);
        mouseMoveImpl(xgc, p != null ? p.x : x, p != null ? p.y : y);
        if (XdgDesktopPortalRobot.useForInput()) {
            // We still call mouseMoveImpl on purpose to change the mouse position
            // within the XWayland server so that we can retrieve it later.
            XdgDesktopPortalRobot.mouseMove(p != null ? p.x : x, p != null ? p.y : y);
        }
    }

    @Override
    public void mousePress(int buttons) {
        if (XdgDesktopPortalRobot.useForInput()) {
            XdgDesktopPortalRobot.mouseButton(true, buttons);
        } else {
            mousePressImpl(buttons);
        }
    }

    @Override
    public void mouseRelease(int buttons) {
        if (XdgDesktopPortalRobot.useForInput()) {
            XdgDesktopPortalRobot.mouseButton(false, buttons);
        } else {
            mouseReleaseImpl(buttons);
        }
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        if (XdgDesktopPortalRobot.useForInput()) {
            XdgDesktopPortalRobot.mouseWheel(wheelAmt);
        } else {
            mouseWheelImpl(wheelAmt);
        }
    }

    @Override
    public void keyPress(int keycode) {
        if (XdgDesktopPortalRobot.useForInput()) {
            XdgDesktopPortalRobot.key(true, keycode);
        } else {
            keyPressImpl(keycode);
        }
    }

    @Override
    public void keyRelease(int keycode) {
        if (XdgDesktopPortalRobot.useForInput()) {
            XdgDesktopPortalRobot.key(false, keycode);
        } else {
            keyReleaseImpl(keycode);
        }
    }

    @Override
    public int getRGBPixel(int x, int y) {
        if (XdgDesktopPortalRobot.useForCapture()) {
            return XdgDesktopPortalRobot.getRGBPixel(x, y);
        }
        int[] pixelArray = new int[1];
        getRGBPixelsImpl(xgc, x, y, 1, 1, pixelArray, useGtk);
        return pixelArray[0];
    }

    @Override
    public int[] getRGBPixels(Rectangle bounds) {
        if (XdgDesktopPortalRobot.useForCapture()) {
            return XdgDesktopPortalRobot.getRGBPixels(bounds);
        }
        int[] pixelArray = new int[bounds.width * bounds.height];
        getRGBPixelsImpl(xgc, bounds.x, bounds.y, bounds.width, bounds.height, pixelArray, useGtk);
        return pixelArray;
    }

    private static synchronized native void setup(int numberOfButtons, int[] buttonDownMasks);
    private static native void loadNativeLibraries();

    private static synchronized native void mouseMoveImpl(X11GraphicsConfig xgc, int x, int y);
    private static synchronized native void mousePressImpl(int buttons);
    private static synchronized native void mouseReleaseImpl(int buttons);
    private static synchronized native void mouseWheelImpl(int wheelAmt);

    private static synchronized native void keyPressImpl(int keycode);
    private static synchronized native void keyReleaseImpl(int keycode);

    private static synchronized native void getRGBPixelsImpl(X11GraphicsConfig xgc,
            int x, int y, int width, int height, int[] pixelArray, boolean isGtkSupported);
}
