/*
 * Copyright (c) 2003, 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.peer.MouseInfoPeer;

import sun.awt.AWTAccessor;
import sun.awt.X11GraphicsDevice;

public final class XMouseInfoPeer implements MouseInfoPeer {

    /**
     * Package-private constructor to prevent instantiation.
     */
    XMouseInfoPeer() {
    }

    public int fillPointWithCoords(Point point) {
        long display = XToolkit.getDisplay();
        GraphicsEnvironment ge = GraphicsEnvironment.
                                     getLocalGraphicsEnvironment();
        GraphicsDevice[] gds = ge.getScreenDevices();
        int gdslen = gds.length;

        XToolkit.awtLock();
        try {
            for (int i = 0; i < gdslen; i++) {
                long screenRoot = XlibWrapper.RootWindow(display, i);
                boolean pointerFound = XlibWrapper.XQueryPointer(
                                           display, screenRoot,
                                           XlibWrapper.larg1,  // root_return
                                           XlibWrapper.larg2,  // child_return
                                           XlibWrapper.larg3,  // xr_return
                                           XlibWrapper.larg4,  // yr_return
                                           XlibWrapper.larg5,  // xw_return
                                           XlibWrapper.larg6,  // yw_return
                                           XlibWrapper.larg7); // mask_return
                if (pointerFound) {
                    point.x = Native.getInt(XlibWrapper.larg3);
                    point.y = Native.getInt(XlibWrapper.larg4);
                    // In virtual screen environment XQueryPointer returns true only for the
                    // default screen, so search all available devices to determine correct scale.
                    // Note that in XWayland mode, pointer position is returned only when over
                    // owned or *some other* windows, so it may "freeze" when moving over
                    // desktop or some non-owned windows.
                    boolean independentScreens = true;
                    int nearestScreen = i, nearestScreenDistance = Integer.MAX_VALUE;
                    for (int j = 0; j < gdslen; j++) {
                        Rectangle bounds = gds[j].getDefaultConfiguration().getBounds();
                        if (bounds.x != 0 || bounds.y != 0) independentScreens = false;
                        if (gds[j] instanceof X11GraphicsDevice d) {
                            bounds.width = d.scaleUp(bounds.width);
                            bounds.height = d.scaleUp(bounds.height);
                        }
                        int dx = Math.max(Math.min(point.x, bounds.x + bounds.width), bounds.x) - point.x;
                        int dy = Math.max(Math.min(point.y, bounds.y + bounds.height), bounds.y) - point.y;
                        int dist = dx*dx + dy*dy;
                        if (dist < nearestScreenDistance) {
                            nearestScreen = j;
                            nearestScreenDistance = dist;
                        }
                    }
                    if (independentScreens) nearestScreen = i;
                    GraphicsDevice device = gds[nearestScreen];
                    if (device instanceof X11GraphicsDevice x11d) {
                        point.x = x11d.scaleDownX(point.x);
                        point.y = x11d.scaleDownY(point.y);
                    }
                    return nearestScreen;
                }
            }
        } finally {
            XToolkit.awtUnlock();
        }

        // this should never happen
        assert false : "No pointer found in the system.";
        return 0;
    }

    @SuppressWarnings("deprecation")
    public boolean isWindowUnderMouse(Window w) {
        if (w == null) {
            return false;
        }
        XWindow peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer == null) {
            return false;
        }
        long display = XToolkit.getDisplay();
        long contentWindow = peer.getContentWindow();
        long parent = XlibUtil.getParentWindow(contentWindow);

        XToolkit.awtLock();
        try
        {
            boolean windowOnTheSameScreen = XlibWrapper.XQueryPointer(display, parent,
                                  XlibWrapper.larg1, // root_return
                                  XlibWrapper.larg8, // child_return
                                  XlibWrapper.larg3, // root_x_return
                                  XlibWrapper.larg4, // root_y_return
                                  XlibWrapper.larg5, // win_x_return
                                  XlibWrapper.larg6, // win_y_return
                                  XlibWrapper.larg7); //  mask_return
            long siblingWindow = Native.getWindow(XlibWrapper.larg8);
            return (siblingWindow == contentWindow && windowOnTheSameScreen);
        }
        finally
        {
            XToolkit.awtUnlock();
        }
    }
}
