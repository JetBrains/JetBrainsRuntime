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

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;

/**
 * A wayland "pointer" (mouse/surface/trackpad) event received from the native code.
 * It accumulates one or more events that occurred since the last time and gets sent
 * when Wayland generates the "frame" event denoting some sort of finished state
 * of the pointer input.
 * Therefore, not all fields are valid at all times and the many 'hasXXX()' methods
 * have to be consulted prior to calling any of the 'getXXX()' ones.
 * For example, the event with only 'has_leave_event' will not have 'surface_x'
 * or 'timestamp' set.
 * The unset fields are initialized to their default values (0 or false).
 * Refer to wayland.xml for the full documentation.
 */
class WLPointerEvent {
    private boolean has_enter_event;
    private boolean has_leave_event;
    private boolean has_motion_event;
    private boolean has_button_event;
    private boolean has_axis_event;

    private long    surface; /// 'struct wl_surface *' this event appertains to
    private long    serial;
    private long    timestamp;

    private int     surface_x;
    private int     surface_y;

    private int     buttonCode; // pointer button code corresponding to PointerButtonCodes.linuxCode
    private boolean isButtonPressed; // true if button was pressed, false if released

    private boolean axis_0_valid; // is vertical scroll included in this event?
    private int     axis_0_value; // "length of vector in surface-local coordinate space" (source: wayland.xml)

    private WLPointerEvent() {}

    static WLPointerEvent newInstance() {
        // Invoked from the native code
        return new WLPointerEvent();
    }

    public enum PointerButtonCodes {
        // see <linux/input-event-codes.h>
        LEFT(0x110, MouseEvent.BUTTON1),
        MIDDLE(0x112, MouseEvent.BUTTON2),
        RIGHT(0x111, MouseEvent.BUTTON3),

        // Extra mouse buttons
        // Most mice use BTN_SIDE for backward, BTN_EXTRA for forward.
        // There are also BTN_FORWARD and BTN_BACK, but they are different buttons, it seems.
        // On Logitech MX Master 3S, BTN_FORWARD is the thumb button.
        // The default X11 configuration has them as mouse button 8 and 9 respectfully,
        // XToolkit converts them to Java codes by subtracting 2.
        // Then, IDEA converts them to its internal codes by also subtracting 2
        // This is because on X11 the mouse button numbering is as follows:
        //   - 1: Left
        //   - 2: Middle
        //   - 3: Right
        //   - 4: Vertical scroll up
        //   - 5: Vertical scroll down
        //   - 6: Horizontal scroll left
        //   - 7: Horizontal scroll right
        //   - 8: Backwards
        //   - 9: Forwards
        // Since the buttons 4-7 are not actually buttons, they are ignored
        // They will be skipped in WLToolkit

        FORWARD(0x115, 6),
        BACK(0x116, 7),
        SIDE(0x113, 4),
        EXTRA(0x114, 5),

        ;

        public final int linuxCode; // The code from <linux/input-event-codes.h>
        public final int javaCode;  // The code from MouseEvents.BUTTONx

        PointerButtonCodes(int linuxCode, int javaCode) {
            this.linuxCode = linuxCode;
            this.javaCode  = javaCode;
        }

        public int mask() {
            return InputEvent.getMaskForButton(javaCode);
        }

        static PointerButtonCodes recognizedOrNull(int linuxCode) {
            for (var e : values()) {
                if (e.linuxCode == linuxCode) {
                    return e;
                }
            }
            return null;
        }

        static boolean anyMatchMask(int mask) {
            for (var c : values()) {
                if ((mask & c.mask()) != 0) {
                    return true;
                }
            }

            return false;
        }

        static int combinedMask() {
            int mask = 0;
            for (var c : values()) {
                mask |= c.mask();
            }
            return mask;
        }

        public String toString() {
            return switch (this) {
                case LEFT   -> "left";
                case MIDDLE -> "middle";
                case RIGHT  -> "right";
                case FORWARD -> "forward";
                case BACK -> "back";
                case SIDE -> "side";
                case EXTRA -> "extra";
            };
        }

        public boolean isPopupTrigger() {
            // TODO: this should probably be configurable for left- and right-handed?
            return this == RIGHT;
        }
    }

    public boolean hasEnterEvent() {
        return has_enter_event;
    }

    public boolean hasLeaveEvent() {
        return has_leave_event;
    }

    public boolean hasMotionEvent() {
        return has_motion_event;
    }

    public boolean hasButtonEvent() {
        return has_button_event;
    }

    public boolean hasAxisEvent() {
        return has_axis_event;
    }

    /**
     * @return true if this event's field 'surface' is valid.
     */
    public boolean hasSurface() {
        return hasEnterEvent() || hasLeaveEvent();
    }

    /**
     * @return true if this event's field 'serial' is valid.
     */
    public boolean hasSerial() {
        return hasEnterEvent() || hasLeaveEvent() || hasButtonEvent();
    }

    /**
     * @return true if this event's field 'timestamp' is valid.
     */
    public boolean hasTimestamp() {
        return hasMotionEvent() || hasButtonEvent();
    }

    /**
     * @return true if this event's fields 'surface_x' and 'surface_y' are valid.
     */
    public boolean hasCoordinates() {
        return hasMotionEvent() || hasEnterEvent();
    }

    public long getSurface() {
        assert hasSurface();
        return surface;
    }

    public long getSerial() {
        assert hasSerial();
        return serial;
    }

    public long getTimestamp() {
        assert hasTimestamp();
        return timestamp;
    }

    public int getSurfaceX() {
        assert hasCoordinates();
        return surface_x;
    }

    public int getSurfaceY() {
        assert hasCoordinates();
        return surface_y;
    }

    public int getButtonCode() {
        assert hasButtonEvent();
        return buttonCode;
    }

    public boolean getIsButtonPressed() {
        assert hasButtonEvent();
        return isButtonPressed;
    }

    public boolean getIsAxis0Valid() {
        assert hasAxisEvent();
        return axis_0_valid;
    }

    public int getAxis0Value() {
        assert hasAxisEvent();
        return axis_0_value;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("WLPointerEvent(");
        if (hasSurface()) {
            builder.append("surface: 0x").append(Long.toHexString(getSurface()));
        }

        if (hasSerial()) {
            builder.append(", serial: ").append(getSerial());
        }

        if (hasTimestamp()) {
            builder.append(", timestamp: ").append(getTimestamp());
        }

        if (hasEnterEvent()) builder.append(" enter");
        if (hasLeaveEvent()) builder.append(" leave");

        if (hasMotionEvent()) {
            builder.append(" motion ");
            builder.append("x: ").append(getSurfaceX()).append(", y: ").append(getSurfaceY());

        }
        if (hasButtonEvent()) {
            builder.append(" button ");

            builder.append(buttonCodeToString(getButtonCode())).append(" ");
            builder.append(getIsButtonPressed() ? "pressed" : "released");
        }

        if (hasAxisEvent()) {
            builder.append("axis");
            if (axis_0_valid) {
                builder.append("vertical-scroll: ").append(axis_0_value).append(" ");
            }
        }

        builder.append(")");

        return builder.toString();
    }

    private static String buttonCodeToString(int code) {
        final PointerButtonCodes recognizedCode = PointerButtonCodes.recognizedOrNull(code);
        return String.valueOf(recognizedCode);
    }
}