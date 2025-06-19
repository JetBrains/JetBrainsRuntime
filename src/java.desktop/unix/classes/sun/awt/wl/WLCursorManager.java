/*
 * Copyright (c) 2025-2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025-2025, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.AWTAccessor;
import sun.util.logging.PlatformLogger;

import java.awt.Cursor;
import java.awt.GraphicsConfiguration;

public class WLCursorManager {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLCursorManager");

    // mapping of AWT cursor types to X cursor names
    // multiple variants can be specified, that will be tried in order
    // See https://freedesktop.org/wiki/Specifications/cursor-spec/
    private static final String[][] CURSOR_NAMES = {
            {"default", "arrow", "left_ptr", "left_arrow"}, // DEFAULT_CURSOR
            {"crosshair", "cross"}, // CROSSHAIR_CURSOR
            {"text", "xterm"}, // TEXT_CURSOR
            {"wait", "watch", "progress"}, // WAIT_CURSOR
            {"sw-resize", "bottom_left_corner"}, // SW_RESIZE_CURSOR
            {"se-resize", "bottom_right_corner"}, // SE_RESIZE_CURSOR
            {"nw-resize", "top_left_corner"}, // NW_RESIZE_CURSOR
            {"ne-resize", "top_right_corner"}, // NE_RESIZE_CURSOR
            {"n-resize", "top_side"}, // N_RESIZE_CURSOR
            {"s-resize", "bottom_side"}, // S_RESIZE_CURSOR
            {"w-resize", "left_side"}, // W_RESIZE_CURSOR
            {"e-resize", "right_side"}, // E_RESIZE_CURSOR
            {"pointer", "pointing_hand", "hand1", "hand2"}, // HAND_CURSOR
            {"move"}, // MOVE_CURSOR
    };

    private static final WLCursorManager instance = new WLCursorManager();

    private Cursor currentCursor; // accessed under the 'instance' lock

    public static WLCursorManager getInstance() {
        return instance;
    }

    private WLCursorManager() {
    }

    public void reset() {
        synchronized (instance) {
            currentCursor = null;
        }
    }

    public void updateCursorImmediatelyFor(WLComponentPeer peer) {
        var inputState = WLToolkit.getInputState();
        Cursor cursor = peer.cursorAt(inputState.getPointerX(), inputState.getPointerY());
        GraphicsConfiguration gc = peer.getGraphicsConfiguration();
        int scale = gc instanceof WLGraphicsConfig wlGC ? wlGC.getDisplayScale() : 1;
        setCursorTo(cursor, scale);
    }

    private void setCursorTo(Cursor c, int scale) {
        var inputState = WLToolkit.getInputState();

        long serial = inputState.pointerEnterSerial();
        if (serial == 0) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("setCursor aborted due to missing event serial");
            }
            return; // Wayland will ignore the request anyway
        }

        Cursor cursor;
        if (c.getType() == Cursor.CUSTOM_CURSOR && !(c instanceof WLCustomCursor)) {
            cursor = Cursor.getDefaultCursor();
        } else {
            cursor = c;
        }

        // Native methods that work with cursor are not thread-safe, so we need to synchronize access to them.
        synchronized (instance) {
            // Cursors may get updated very often; prevent vacuous updates from reaching the native code so
            // as not to overwhelm the Wayland server.
            if (cursor == currentCursor) return;
            currentCursor = cursor;

            long pData = AWTAccessor.getCursorAccessor().getPData(cursor, scale);
            if (pData == 0) {
                // TODO: instead of destroying and creating a new cursor after changing the scale caching could be used
                long oldPData = AWTAccessor.getCursorAccessor().getPData(cursor);
                if (oldPData != 0 && oldPData != -1) {
                    nativeDestroyPredefinedCursor(oldPData);
                }

                pData = createNativeCursor(cursor.getType(), scale);
                if (pData == 0) {
                    pData = createNativeCursor(Cursor.DEFAULT_CURSOR, scale);
                }
                if (pData == 0) {
                    pData = -1; // mark as unavailable
                }
                AWTAccessor.getCursorAccessor().setPData(cursor, scale, pData);
            }
            nativeSetCursor(pData, scale, serial);
        }
    }

    private long createNativeCursor(int type, int scale) {
        if (type < Cursor.DEFAULT_CURSOR || type > Cursor.MOVE_CURSOR) {
            type = Cursor.DEFAULT_CURSOR;
        }
        for (String name : CURSOR_NAMES[type]) {
            long pData = nativeGetPredefinedCursor(name, scale);
            if (pData != 0) {
                return pData;
            }
        }
        return 0;
    }

    private static native void nativeSetCursor(long pData, int scale, long pointerEnterSerial);
    private static native long nativeGetPredefinedCursor(String name, int scale);
    private static native void nativeDestroyPredefinedCursor(long pData);
}
