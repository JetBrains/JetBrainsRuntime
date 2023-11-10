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

/**
 * MouseEvent objects cannot be created directly from WLPointerEvent because they require
 * the information of certain events from the past like keyboard modifiers keys getting
 * pressed. WLInputState maintains this information.
 *
 * @param eventWithSurface null or the latest PointerEvent such that hasSurface() == true
 * @param eventWithSerial null or the latest PointerEvent such that hasSerial() == true
 * @param eventWithTimestamp null or the latest PointerEvent such that hasTimestamp() == true
 * @param eventWithCoordinates null or the latest PointerEvent such that hasCoordinates() == true
 * @param pointerButtonPressedEvent null or the latest PointerButtonEvent such that getIsButtonPressed() == true
 * @param modifiers a bit set of modifiers reflecting currently pressed keys (@see WLInputState.getNewModifiers())
 * @param surfaceForKeyboardInput represents 'struct wl_surface*' that keyboards events should go to
 */
record WLInputState(WLPointerEvent eventWithSurface,
                    WLPointerEvent eventWithSerial,
                    WLPointerEvent eventWithTimestamp,
                    WLPointerEvent eventWithCoordinates,
                    PointerButtonEvent pointerButtonPressedEvent,
                    int modifiers,
                    long surfaceForKeyboardInput,
                    boolean isPointerOverSurface) {
    /**
     * Groups together information about a mouse pointer button event.
     * @param surface 'struct wl_surface*' the button was pressed over
     * @param serial serial number of the event as received from Wayland
     * @param timestamp time of the event as received from Wayland
     * @param clickCount number of consecutive clicks of the same button performed
     *                   within WLToolkit.getMulticlickTime() milliseconds from one another
     * @param linuxCode button code corresponding to WLPointerEvent.PointerButtonCodes.linuxCode
     */
    record PointerButtonEvent(long surface, long serial, long timestamp, int clickCount, int linuxCode) {}

    static WLInputState initialState() {
        return new WLInputState(null, null, null, null,
                null, 0, 0, false);
    }

    /**
     * Creates new state based on the existing one and the supplied WLPointerEvent.
     */
    WLInputState update(WLPointerEvent pointerEvent) {
        final WLPointerEvent newEventWithSurface = pointerEvent.hasSurface()
                ? pointerEvent : eventWithSurface;
        final WLPointerEvent newEventWithSerial = pointerEvent.hasSerial()
                ? pointerEvent : eventWithSerial;
        final WLPointerEvent newEventWithTimestamp = pointerEvent.hasTimestamp()
                ? pointerEvent : eventWithTimestamp;
        final WLPointerEvent newEventWithCoordinates = pointerEvent.hasCoordinates()
                ? pointerEvent : eventWithCoordinates;
        final PointerButtonEvent newPointerButtonEvent = getNewPointerButtonEvent(pointerEvent,
                newEventWithSurface,
                newEventWithSerial,
                newEventWithTimestamp);
        final int newModifiers = getNewModifiers(pointerEvent);

        boolean newPointerOverSurface = (pointerEvent.hasEnterEvent() || isPointerOverSurface)
                && !pointerEvent.hasLeaveEvent();

        return new WLInputState(
                newEventWithSurface,
                newEventWithSerial,
                newEventWithTimestamp,
                newEventWithCoordinates,
                newPointerButtonEvent,
                newModifiers,
                surfaceForKeyboardInput,
                newPointerOverSurface);
    }

    public WLInputState updatedFromKeyboardEnterEvent(long serial, long surfacePtr) {
        // "The compositor must send the wl_keyboard.modifiers event after this event".
        return new WLInputState(
                eventWithSurface,
                eventWithSerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                modifiers,
                surfacePtr,
                isPointerOverSurface);
    }

    public WLInputState updatedFromKeyboardModifiersEvent(long serial, int keyboardModifiers) {
        // "The compositor must send the wl_keyboard.modifiers event after this event".
        final int oldPointerModifiers = modifiers & WLPointerEvent.PointerButtonCodes.combinedMask();
        final int newModifiers = oldPointerModifiers | keyboardModifiers;
        return new WLInputState(
                eventWithSurface,
                eventWithSerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                newModifiers,
                surfaceForKeyboardInput,
                isPointerOverSurface);
    }

    public WLInputState updatedFromKeyboardLeaveEvent(long serial, long surfacePtr) {
        // "After this event client must assume that all keys, including modifiers,
        //	are lifted and also it must stop key repeating if there's some going on".
        final int newModifiers = modifiers & WLPointerEvent.PointerButtonCodes.combinedMask();
        return new WLInputState(
                eventWithSurface,
                eventWithSerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                newModifiers,
                0,
                isPointerOverSurface);
    }

    public WLInputState resetPointerState() {
        return new WLInputState(
                eventWithSurface,
                eventWithSerial,
                eventWithTimestamp,
                eventWithCoordinates,
                pointerButtonPressedEvent,
                0,
                surfaceForKeyboardInput,
                false);
    }

    private PointerButtonEvent getNewPointerButtonEvent(WLPointerEvent pointerEvent,
                                                        WLPointerEvent newEventWithSurface,
                                                        WLPointerEvent newEventWithSerial,
                                                        WLPointerEvent newEventWithTimestamp) {
        if (pointerEvent.hasButtonEvent() && pointerEvent.getIsButtonPressed() && newEventWithSurface != null) {
            assert newEventWithSerial != null && newEventWithTimestamp != null;
            int clickCount = 1;
            final boolean pressedSameButton = pointerButtonPressedEvent != null
                    && pointerEvent.getButtonCode() == pointerButtonPressedEvent.linuxCode;
            if (pressedSameButton) {
                final boolean clickedSameSurface
                        = newEventWithSurface.getSurface() == pointerButtonPressedEvent.surface;
                final boolean clickedQuickly
                        = (pointerEvent.getTimestamp() - pointerButtonPressedEvent.timestamp)
                        <= WLToolkit.getMulticlickTime();
                if (clickedSameSurface && clickedQuickly) {
                    clickCount = pointerButtonPressedEvent.clickCount + 1;
                }
            }

            return new PointerButtonEvent(
                    newEventWithSurface.getSurface(),
                    newEventWithSerial.getSerial(),
                    newEventWithTimestamp.getTimestamp(),
                    clickCount,
                    pointerEvent.getButtonCode());
        }

        return pointerButtonPressedEvent;
    }

    private int getNewModifiers(WLPointerEvent pointerEvent) {
        int newModifiers = modifiers;

        if (pointerEvent.hasLeaveEvent()) {
            return 0;
        }

        if (pointerEvent.hasButtonEvent()) {
            final WLPointerEvent.PointerButtonCodes buttonCode
                    = WLPointerEvent.PointerButtonCodes.recognizedOrNull(pointerEvent.getButtonCode());
            if (buttonCode != null) {
                if (pointerEvent.getIsButtonPressed()) {
                    newModifiers |= buttonCode.javaMask;
                } else {
                    newModifiers &= ~buttonCode.javaMask;
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

    public long getSurfaceForKeyboardInput() {
        return surfaceForKeyboardInput;
    }

    public int getPointerX() {
        return eventWithCoordinates != null ? eventWithCoordinates.getSurfaceX() : 0;
    }

    public int getPointerY() {
        return eventWithCoordinates != null ? eventWithCoordinates.getSurfaceY() : 0;
    }

    public WLComponentPeer getPeer() {
        return eventWithSurface != null
                ? WLToolkit.componentPeerFromSurface(eventWithSurface.getSurface())
                : null;
    }

    /**
     * @return true if pointer has entered the associated peer and has not left its bounds.
     */
    public boolean isPointerOverPeer() {
        if (isPointerOverSurface && eventWithCoordinates != null) {
            int x = eventWithCoordinates.getSurfaceX();
            int y = eventWithCoordinates.getSurfaceY();
            WLComponentPeer peer = getPeer();
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
}