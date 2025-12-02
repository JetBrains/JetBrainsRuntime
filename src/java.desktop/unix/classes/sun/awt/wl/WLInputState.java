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

/**
 * MouseEvent objects cannot be created directly from WLPointerEvent because they require
 * the information of certain events from the past like keyboard modifiers keys getting
 * pressed. WLInputState maintains this information.
 *
 * @param eventWithSurface null or the latest WLPointerEvent such that hasSurface() == true
 * @param pointerEnterSerial zero or the serial of the latest wl_pointer::enter event
 * @param pointerButtonSerial zero or the serial of the latest wl_pointer::button event
 * @param keyboardEnterSerial zero or the serial of the latest wl_keyboard::enter event
 * @param keySerial zero or the serial of the latest wl_keyboard::key event
 * @param eventWithTimestamp null or the latest WLPointerEvent such that hasTimestamp() == true
 * @param eventWithCoordinates null or the latest WLPointerEvent such that hasCoordinates() == true
 * @param pointerButtonPressedEvent null or the latest PointerButtonEvent such that getIsButtonPressed() == true
 * @param modifiers a bit set of modifiers reflecting currently pressed keys (@see WLInputState.getNewModifiers())
 * @param surfaceForKeyboardInput represents 'struct wl_surface*' that keyboards events should go to
 * @param isPointerOverSurface true if the mouse pointer has entered a surface and has not left yet
 * @param latestInputSerial the serial of the latest input event (key or pointer button press)
 */
record WLInputState(WLPointerEvent eventWithSurface,
                    long pointerEnterSerial,
                    long pointerButtonSerial,
                    long keyboardEnterSerial,
                    long keySerial,
                    WLPointerEvent eventWithTimestamp,
                    WLPointerEvent eventWithCoordinates,
                    PointerButtonEvent pointerButtonPressedEvent,
                    int modifiers,
                    long surfaceForKeyboardInput,
                    boolean isPointerOverSurface,
                    long latestInputSerial) {
    /**
     * Groups together information about a mouse pointer button event.
     * @param surface 'struct wl_surface*' the button was pressed over
     * @param timestamp time of the event as received from Wayland
     * @param clickCount number of consecutive clicks of the same button performed
     *                   within WLToolkit.getMulticlickTime() milliseconds from one another
     * @param linuxCode button code corresponding to WLPointerEvent.PointerButtonCodes.linuxCode
     * @param surfaceX the X coordinate of the button press relative to the surface
     * @param surfaceY the Y coordinate of the button press relative to the surface
     */
    record PointerButtonEvent(
            long surface,
            long timestamp,
            int clickCount,
            int linuxCode,
            int surfaceX,
            int surfaceY) {}

    static WLInputState initialState() {
        return new WLInputState(null, 0, 0, 0, 0, null, null,
                null, 0, 0, false, 0);
    }

    /**
     * Creates a new state based on the existing one and the supplied WLPointerEvent.
     */
    WLInputState updatedFromPointerEvent(WLPointerEvent pointerEvent) {
        final WLPointerEvent newEventWithSurface = pointerEvent.hasSurface()
                ? pointerEvent : eventWithSurface;
        final long newPointerEnterSerial = pointerEvent.hasEnterEvent()
                ? pointerEvent.getSerial() : pointerEnterSerial;
        final long newPointerButtonSerial = pointerEvent.hasButtonEvent()
                ? pointerEvent.getSerial() : pointerButtonSerial;
        final WLPointerEvent newEventWithTimestamp = pointerEvent.hasTimestamp()
                ? pointerEvent : eventWithTimestamp;
        final WLPointerEvent newEventWithCoordinates = pointerEvent.hasCoordinates()
                ? pointerEvent : eventWithCoordinates;
        final PointerButtonEvent newPointerButtonEvent = getNewPointerButtonEvent(pointerEvent,
                newEventWithSurface,
                newEventWithTimestamp,
                newEventWithCoordinates);
        final int newModifiers = getNewModifiers(pointerEvent);

        boolean newPointerOverSurface = (pointerEvent.hasEnterEvent() || isPointerOverSurface)
                && !pointerEvent.hasLeaveEvent();

        final long newLatestInputEventSerial = pointerEvent.hasButtonEvent()
                ? pointerEvent.getSerial() : latestInputSerial;

        return new WLInputState(
                newEventWithSurface,
                newPointerEnterSerial,
                newPointerButtonSerial,
                keyboardEnterSerial,
                keySerial,
                newEventWithTimestamp,
                newEventWithCoordinates,
                newPointerButtonEvent,
                newModifiers,
                surfaceForKeyboardInput,
                newPointerOverSurface,
                newLatestInputEventSerial);
    }

    public WLInputState updatedFromKeyEvent(long serial) {
        return new WLInputState(
                eventWithSurface,
                pointerEnterSerial,
                pointerButtonSerial,
                keyboardEnterSerial,
                serial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                modifiers,
                surfaceForKeyboardInput,
                isPointerOverSurface,
                serial);
    }

    public WLInputState updatedFromKeyboardEnterEvent(long serial, long surfacePtr) {
        // "The compositor must send the wl_keyboard.modifiers event after this event".
        return new WLInputState(
                eventWithSurface,
                pointerEnterSerial,
                pointerButtonSerial,
                serial,
                0,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                modifiers,
                surfacePtr,
                isPointerOverSurface,
                latestInputSerial);
    }

    public WLInputState updatedFromKeyboardModifiersEvent(long serial, int keyboardModifiers) {
        // NB: there seem to be no use for the serial number of this kind of event so far
        final int oldPointerModifiers = modifiers & WLPointerEvent.PointerButtonCodes.combinedMask();
        final int newModifiers = oldPointerModifiers | keyboardModifiers;
        return new WLInputState(
                eventWithSurface,
                pointerEnterSerial,
                pointerButtonSerial,
                keyboardEnterSerial,
                keySerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                newModifiers,
                surfaceForKeyboardInput,
                isPointerOverSurface,
                latestInputSerial);
    }

    public WLInputState updatedFromKeyboardLeaveEvent(long serial, long surfacePtr) {
        // NB: there seem to be no use for the serial number of this kind of event so far

        // "After this event client must assume that all keys, including modifiers,
        //	are lifted and also it must stop key repeating if there's some going on".

        // We learned from experience that some serials become invalid after focus lost, so they
        // are zeroed-out to prevent the use of a stale serial.
        // Note: Wayland doesn't report failure when a stale serial is passed.
        final int newModifiers = modifiers & WLPointerEvent.PointerButtonCodes.combinedMask();
        return new WLInputState(
                eventWithSurface,
                pointerEnterSerial,
                0,
                0,
                0,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                newModifiers,
                0,
                isPointerOverSurface,
                latestInputSerial);
    }

    public WLInputState updatedFromUnregisteredSurface(long surfacePtr) {
        if (surfaceForKeyboardInput == surfacePtr) {
            // When a window is hidden, we don't receive the keyboard.leave event, but the surface
            // becomes stale and its use dangerous, so must clear it out.
            return new WLInputState(
                    eventWithSurface,
                    pointerEnterSerial,
                    pointerButtonSerial,
                    keyboardEnterSerial,
                    keySerial,
                    eventWithTimestamp,
                    eventWithCoordinates,
                    pointerButtonPressedEvent,
                    modifiers,
                    0,
                    isPointerOverSurface,
                    latestInputSerial);
        } else {
            return this;
        }
    }

    public WLInputState resetPointerState() {
        return new WLInputState(
                eventWithSurface,
                pointerEnterSerial,
                pointerButtonSerial,
                keyboardEnterSerial,
                keySerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                modifiers & ~WLPointerEvent.PointerButtonCodes.combinedMask(),
                surfaceForKeyboardInput,
                false,
                latestInputSerial);
    }

    private PointerButtonEvent getNewPointerButtonEvent(WLPointerEvent pointerEvent,
                                                        WLPointerEvent newEventWithSurface,
                                                        WLPointerEvent newEventWithTimestamp,
                                                        WLPointerEvent newEventWithPosition) {
        if (pointerEvent.hasButtonEvent() && pointerEvent.getIsButtonPressed() && newEventWithSurface != null) {
            assert newEventWithTimestamp != null && newEventWithPosition != null
                    : "Events with timestamp and position are both required to be present";

            int clickCount = 1;
            final boolean pressedSameButton = pointerButtonPressedEvent != null
                    && pointerEvent.getButtonCode() == pointerButtonPressedEvent.linuxCode;
            if (pressedSameButton) {
                final boolean clickedSameSurface
                        = newEventWithSurface.getSurface() == pointerButtonPressedEvent.surface;
                final boolean clickedQuickly
                        = (pointerEvent.getTimestamp() - pointerButtonPressedEvent.timestamp)
                        <= WLToolkit.getMulticlickTime();
                final boolean mouseDidNotMove =
                        newEventWithPosition.getSurfaceX() == pointerButtonPressedEvent.surfaceX &&
                        newEventWithPosition.getSurfaceY() == pointerButtonPressedEvent.surfaceY;
                if (clickedSameSurface && clickedQuickly && mouseDidNotMove) {
                    clickCount = pointerButtonPressedEvent.clickCount + 1;
                }
            }

            return new PointerButtonEvent(
                    newEventWithSurface.getSurface(),
                    newEventWithTimestamp.getTimestamp(),
                    clickCount,
                    pointerEvent.getButtonCode(),
                    newEventWithPosition.getSurfaceX(),
                    newEventWithPosition.getSurfaceY());
        }

        return pointerButtonPressedEvent;
    }

    private int getNewModifiers(WLPointerEvent pointerEvent) {
        int newModifiers = modifiers;

        if (pointerEvent.hasLeaveEvent()) {
            return modifiers & ~WLPointerEvent.PointerButtonCodes.combinedMask();
        }

        if (pointerEvent.hasButtonEvent()) {
            final WLPointerEvent.PointerButtonCodes buttonCode
                    = WLPointerEvent.PointerButtonCodes.recognizedOrNull(pointerEvent.getButtonCode());
            if (buttonCode != null) {
                if (pointerEvent.getIsButtonPressed()) {
                    newModifiers |= buttonCode.mask();
                } else {
                    newModifiers &= ~buttonCode.mask();
                }
            }
        }
        return newModifiers;
    }

    public boolean hasThisPointerButtonPressed(int linuxCode) {
        return pointerButtonPressedEvent != null && pointerButtonPressedEvent.linuxCode == linuxCode;
    }

    public boolean hasPointerButtonPressed() {
        return WLPointerEvent.PointerButtonCodes.anyMatchMask(modifiers);
    }

    public int getPointerX() {
        int x = eventWithCoordinates != null ? eventWithCoordinates.getSurfaceX() : 0;
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return x;
        } else {
            WLComponentPeer peer = peerForPointerEvents();
            return peer == null ? x : peer.surfaceUnitsToJavaUnits(x);
        }
    }

    public int getPointerY() {
        int y = eventWithCoordinates != null ? eventWithCoordinates.getSurfaceY() : 0;
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return y;
        } else {
            WLComponentPeer peer = peerForPointerEvents();
            return peer == null ? y : peer.surfaceUnitsToJavaUnits(y);
        }
    }

    /**
     * Which peer mouse pointer events should be delivered to, if any.
     *
     * @return WLComponentPeer instance corresponding to the Wayland surface that
     *         received pointer-related events the last
     */
    public WLComponentPeer peerForPointerEvents() {
        return eventWithSurface != null
                ? WLToolkit.peerFromSurface(eventWithSurface.getSurface())
                : null;
    }

    /**
     * @return true if pointer has entered the associated peer and has not left its bounds.
     */
    public boolean isPointerOverPeer() {
        if (isPointerOverSurface && eventWithCoordinates != null) {
            int x = getPointerX();
            int y = getPointerY();
            WLComponentPeer peer = peerForPointerEvents();
            if (peer != null) {
                return x >= 0
                        && x < peer.getWidth()
                        && y >= 0
                        && y < peer.getHeight();
            }
        }
        return true;
    }

    public long getTimestamp() {
        return eventWithTimestamp != null ? eventWithTimestamp.getTimestamp() : 0;
    }

    public int getClickCount() {
        return pointerButtonPressedEvent != null ? pointerButtonPressedEvent.clickCount : 1;
    }

    public int getModifiers() {
        return modifiers;
    }

    public int getNonKeyboardModifiers() {
        return modifiers & ~(InputEvent.SHIFT_DOWN_MASK |  InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
    }
}