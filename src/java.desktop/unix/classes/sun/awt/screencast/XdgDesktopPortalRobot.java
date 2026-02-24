/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.screencast;

import java.awt.Rectangle;

public final class XdgDesktopPortalRobot {
    private XdgDesktopPortalRobot() {}

    public static boolean useForInput() {
        return XdgDesktopPortal.isRemoteDesktop() && ScreencastHelper.isAvailable();
    }

    public static boolean useForCapture() {
        return (XdgDesktopPortal.isScreencast() || XdgDesktopPortal.isRemoteDesktop()) && ScreencastHelper.isAvailable();
    }

    public static void mouseMove(int x, int y) {
        ScreencastHelper.remoteDesktopMouseMove(x, y);
    }

    public static void mouseButton(boolean isPress, int buttons) {
        ScreencastHelper.remoteDesktopMouseButton(isPress, buttons);
    }

    public static void mouseWheel(int wheelAmt) {
        ScreencastHelper.remoteDesktopMouseWheel(wheelAmt);
    }

    public static void key(boolean isPress, int keycode) {
        ScreencastHelper.remoteDesktopKey(isPress, keycode);
    }

    public static int getRGBPixel(int x, int y) {
        int[] pixelArray = new int[1];
        ScreencastHelper.getRGBPixels(x, y, 1, 1, pixelArray);
        return pixelArray[0];
    }

    public static int[] getRGBPixels(Rectangle bounds) {
        int[] pixelArray = new int[bounds.width * bounds.height];
        ScreencastHelper.getRGBPixels(bounds.x, bounds.y, bounds.width, bounds.height, pixelArray);
        return pixelArray;
    }
}
