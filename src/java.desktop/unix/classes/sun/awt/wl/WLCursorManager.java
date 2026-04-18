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

    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_DEFAULT = 1;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_CONTEXT_MENU = 2;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_HELP = 3;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_POINTER = 4;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_PROGRESS = 5;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_WAIT = 6;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_CELL = 7;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_CROSSHAIR = 8;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_TEXT = 9;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_VERTICAL_TEXT = 10;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ALIAS = 11;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_COPY = 12;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_MOVE = 13;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NO_DROP = 14;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NOT_ALLOWED = 15;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_GRAB = 16;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_GRABBING = 17;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_E_RESIZE = 18;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_N_RESIZE = 19;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NE_RESIZE = 20;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NW_RESIZE = 21;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_S_RESIZE = 22;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_SE_RESIZE = 23;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_SW_RESIZE = 24;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_W_RESIZE = 25;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_EW_RESIZE = 26;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NS_RESIZE = 27;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NESW_RESIZE = 28;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NWSE_RESIZE = 29;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_COL_RESIZE = 30;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ROW_RESIZE = 31;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ALL_SCROLL = 32;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ZOOM_IN = 33;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ZOOM_OUT = 34;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_DND_ASK = 35;
    private static final int WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_ALL_RESIZE = 36;

    private static final WLCursorManager instance = new WLCursorManager();

    private final boolean isCursorShapeSupported; // Is wp_cursor_shape_manager_v1 supported?

    private Cursor currentCursor; // accessed under the 'instance' lock

    public static WLCursorManager getInstance() {
        return instance;
    }

    private WLCursorManager() {
        isCursorShapeSupported = nativeIsCursorShapeSupported();
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

            boolean isCustomCursor = cursor.getType() == Cursor.CUSTOM_CURSOR;
            if (isCursorShapeSupportedFor(cursor)) {
                long oldPData = AWTAccessor.getCursorAccessor().getPData(cursor);
                if (oldPData != 0 && oldPData != -1) {
                    // Note: this shouldn't normally happen, but in case a predefined cursor
                    // was previously used, this is the only chance to clean up.
                    assert !isCustomCursor : "This code is only meant for predefined cursors cleanup";
                    AWTAccessor.getCursorAccessor().setPData(cursor, 0);
                    nativeDestroyPredefinedCursor(oldPData);
                }
                int shape = getCursorShapeFor(cursor);
                nativeSetCursorShape(shape, serial);
            } else {
                long pData = 0;
                if (isCustomCursor) {
                    pData = AWTAccessor.getCursorAccessor().getPData(cursor);
                    assert pData != 0 : "Custom cursor should have been created before or set to -1 on failure";
                    if (pData == -1) {
                        log.warning("Custom cursor '" + cursor.getName() + "' is unavailable, using invisible cursor instead");
                    }
                } else {
                    pData = AWTAccessor.getCursorAccessor().getPData(cursor, scale);
                    if (pData == 0) {
                        // TODO: instead of destroying and creating a new cursor after changing the scale caching could be used
                        long oldPData = AWTAccessor.getCursorAccessor().getPData(cursor);
                        if (oldPData != 0 && oldPData != -1) {
                            nativeDestroyPredefinedCursor(oldPData);
                        }

                        pData = createNativeCursor(cursor.getType(), scale);
                        if (pData == 0) {
                            log.warning("Failed to create native cursor for type: " + cursor.getType()
                                    + ", using default cursor instead");
                            pData = createNativeCursor(Cursor.DEFAULT_CURSOR, scale);
                        }
                        if (pData == 0) {
                            log.warning("Failed to create default cursor, using invisible cursor instead");
                            pData = -1; // mark as unavailable
                        }
                        AWTAccessor.getCursorAccessor().setPData(cursor, scale, pData);
                    }
                }
                nativeSetCursor(pData, scale, serial);
            }
        }
    }

    private boolean isCursorShapeSupportedFor(Cursor cursor) {
        return isCursorShapeSupported && cursor.getType() != Cursor.CUSTOM_CURSOR;
    }

    private int getCursorShapeFor(Cursor cursor) {
        return switch (cursor.getType()) {
            case Cursor.DEFAULT_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_DEFAULT;
            case Cursor.CROSSHAIR_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_CROSSHAIR;
            case Cursor.TEXT_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_TEXT;
            case Cursor.WAIT_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_WAIT;
            case Cursor.SW_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_SW_RESIZE;
            case Cursor.SE_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_SE_RESIZE;
            case Cursor.NW_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NW_RESIZE;
            case Cursor.NE_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_NE_RESIZE;
            case Cursor.N_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_N_RESIZE;
            case Cursor.W_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_W_RESIZE;
            case Cursor.S_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_S_RESIZE;
            case Cursor.E_RESIZE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_E_RESIZE;
            case Cursor.HAND_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_POINTER;
            case Cursor.MOVE_CURSOR -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_MOVE;
            default -> WP_CURSOR_SHAPE_DEVICE_V1_SHAPE_DEFAULT;
        };
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

    private static native boolean nativeIsCursorShapeSupported();
    private static native void nativeSetCursor(long pData, int scale, long pointerEnterSerial);
    private static native long nativeGetPredefinedCursor(String name, int scale);
    private static native void nativeDestroyPredefinedCursor(long pData);
    private static native void nativeSetCursorShape(int shape, long serial);
}
