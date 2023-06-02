/*
 * Copyright (c) 2011, 2023, Oracle and/or its affiliates. All rights reserved.
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

package sun.lwawt.macosx;

import sun.awt.SunToolkit;
import sun.awt.event.KeyEventProcessing;
import sun.lwawt.LWWindowPeer;
import sun.lwawt.PlatformEventNotifier;

import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates NSEvents/NPCocoaEvents into AWT events.
 */
final class CPlatformResponder {

    private final PlatformEventNotifier eventNotifier;
    private final boolean isNpapiCallback;
    private int lastKeyPressCode = KeyEvent.VK_UNDEFINED;
    private final DeltaAccumulator deltaAccumulatorX = new DeltaAccumulator();
    private final DeltaAccumulator deltaAccumulatorY = new DeltaAccumulator();
    private boolean momentumStarted;
    private int momentumX;
    private int momentumY;
    private int momentumModifiers;
    private int lastDraggedAbsoluteX;
    private int lastDraggedAbsoluteY;
    private int lastDraggedRelativeX;
    private int lastDraggedRelativeY;

    CPlatformResponder(final PlatformEventNotifier eventNotifier,
                       final boolean isNpapiCallback) {
        this.eventNotifier = eventNotifier;
        this.isNpapiCallback = isNpapiCallback;
    }

    /**
     * Handles mouse events.
     */
    void handleMouseEvent(int eventType, int modifierFlags, int buttonNumber,
                          int clickCount, int x, int y, int absX, int absY) {
        final SunToolkit tk = (SunToolkit)Toolkit.getDefaultToolkit();
        if ((buttonNumber > 2 && !tk.areExtraMouseButtonsEnabled())
                || buttonNumber > tk.getNumberOfButtons() - 1) {
            return;
        }

        int jeventType = isNpapiCallback ? NSEvent.npToJavaEventType(eventType) :
                                           NSEvent.nsToJavaEventType(eventType);

        boolean dragged = jeventType == MouseEvent.MOUSE_DRAGGED;
        if (dragged  // ignore dragged event that does not change any location
                && lastDraggedAbsoluteX == absX && lastDraggedRelativeX == x
                && lastDraggedAbsoluteY == absY && lastDraggedRelativeY == y) return;

        if (dragged || jeventType == MouseEvent.MOUSE_PRESSED) {
            lastDraggedAbsoluteX = absX;
            lastDraggedAbsoluteY = absY;
            lastDraggedRelativeX = x;
            lastDraggedRelativeY = y;
        }

        int jbuttonNumber = MouseEvent.NOBUTTON;
        int jclickCount = 0;

        if (jeventType != MouseEvent.MOUSE_MOVED &&
            jeventType != MouseEvent.MOUSE_ENTERED &&
            jeventType != MouseEvent.MOUSE_EXITED)
        {
            jbuttonNumber = NSEvent.nsToJavaButton(buttonNumber);
            jclickCount = clickCount;
        }

        int jmodifiers = NSEvent.nsToJavaModifiers(modifierFlags);
        if ((jeventType == MouseEvent.MOUSE_PRESSED) && (jbuttonNumber > MouseEvent.NOBUTTON)) {
            // 8294426: NSEvent.nsToJavaModifiers returns 0 on M2 MacBooks if the event is generated
            //  via tapping (not pressing) on a trackpad
            //  (System Preferences -> Trackpad -> Tap to click must be turned on).
            // So let's set the modifiers manually.
            jmodifiers |= MouseEvent.getMaskForButton(jbuttonNumber);
        }

        boolean jpopupTrigger = NSEvent.isPopupTrigger(jmodifiers);

        eventNotifier.notifyMouseEvent(jeventType, System.currentTimeMillis(), jbuttonNumber,
                x, y, absX, absY, jmodifiers, jclickCount,
                jpopupTrigger, null);
    }

    /**
     * Handles scroll events.
     */
    void handleScrollEvent(int x, int y, final int absX,
                           final int absY, final int modifierFlags,
                           final double deltaX, final double deltaY,
                           final int scrollPhase) {
        int jmodifiers = NSEvent.nsToJavaModifiers(modifierFlags);

        if (scrollPhase > NSEvent.SCROLL_PHASE_UNSUPPORTED) {
            if (scrollPhase == NSEvent.SCROLL_PHASE_BEGAN) {
                momentumStarted = false;
            } else if (scrollPhase == NSEvent.SCROLL_PHASE_MOMENTUM_BEGAN) {
                momentumStarted = true;
                momentumX = x;
                momentumY = y;
                momentumModifiers = jmodifiers;
            } else if (momentumStarted) {
                x = momentumX;
                y = momentumY;
                jmodifiers = momentumModifiers;
            }
        }
        final boolean isShift = (jmodifiers & InputEvent.SHIFT_DOWN_MASK) != 0;

        int roundDeltaX = deltaAccumulatorX.getRoundedDelta(deltaX, scrollPhase);
        int roundDeltaY = deltaAccumulatorY.getRoundedDelta(deltaY, scrollPhase);

        // Vertical scroll.
        if (!isShift && (deltaY != 0.0 || roundDeltaY != 0)) {
            dispatchScrollEvent(x, y, absX, absY, jmodifiers, roundDeltaY, deltaY);
        }
        // Horizontal scroll or shirt+vertical scroll.
        final double delta = isShift && deltaY != 0.0 ? deltaY : deltaX;
        final int roundDelta = isShift && roundDeltaY != 0 ? roundDeltaY : roundDeltaX;
        if (delta != 0.0 || roundDelta != 0) {
            jmodifiers |= InputEvent.SHIFT_DOWN_MASK;
            dispatchScrollEvent(x, y, absX, absY, jmodifiers, roundDelta, delta);
        }
    }

    private void dispatchScrollEvent(final int x, final int y, final int absX,
                                     final int absY, final int modifiers,
                                     final int roundDelta, final double delta) {
        final long when = System.currentTimeMillis();
        final int scrollType = MouseWheelEvent.WHEEL_UNIT_SCROLL;
        final int scrollAmount = 1;
        // invert the wheelRotation for the peer
        eventNotifier.notifyMouseWheelEvent(when, x, y, absX, absY, modifiers,
                                            scrollType, scrollAmount,
                                            -roundDelta, -delta, null);
    }

    /**
     * Handles key events.
     *
     * @param eventType macOS event type ID: keyDown, keyUp or flagsChanged
     * @param modifierFlags macOS modifier flags mask (NSEventModifierFlags)
     * @param chars NSEvent's characters property
     * @param actualChars If non-null, then this key should generate KEY_TYPED events
     *                    corresponding to characters in this string. Only valid for keyDown events.
     * @param keyCode macOS virtual key code of the key being pressed or released
     * @param needsKeyTyped post KEY_TYPED events?
     * @param needsKeyReleased post KEY_RELEASED events?
     */
    void handleKeyEvent(int eventType, int modifierFlags, String chars, String actualChars,
                        short keyCode, boolean needsKeyTyped, boolean needsKeyReleased) {
        boolean isFlagsChangedEvent =
            isNpapiCallback ? (eventType == CocoaConstants.NPCocoaEventFlagsChanged) :
                              (eventType == CocoaConstants.NSEventTypeFlagsChanged);

        int jeventType = KeyEvent.KEY_PRESSED;
        int jkeyCode = KeyEvent.VK_UNDEFINED;
        int jkeyLocation = KeyEvent.KEY_LOCATION_UNKNOWN;
        boolean spaceKeyTyped = false;

        char testChar = KeyEvent.CHAR_UNDEFINED;

        if (isFlagsChangedEvent) {
            int[] in = new int[] {modifierFlags, keyCode};
            int[] out = new int[3]; // [jkeyCode, jkeyLocation, jkeyType]

            NSEvent.nsKeyModifiersToJavaKeyInfo(in, out);

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = out[2];
        } else {
            if (chars != null && chars.length() > 0) {
                // `chars` might contain more than one character, so why are we using the last one?
                // It doesn't really matter actually! If the string contains more than one character,
                // the only way that this character will be used is to construct the keyChar field of the KeyEvent object.
                // That field is only guaranteed to be meaningful for KEY_TYPED events, so let's not overthink it.
                // Please note: this character is NOT used to construct extended key codes, that happens
                // inside the NSEvent.nsToJavaKeyInfo function.
                testChar = chars.charAt(chars.length() - 1);

                // Check if String chars contains SPACE character.
                if (chars.trim().isEmpty()) {
                    spaceKeyTyped = true;
                }
            }

            int[] in = new int[] {keyCode, KeyEventProcessing.useNationalLayouts ? 1 : 0, KeyEventProcessing.reportDeadKeysAsNormal ? 1 : 0};
            int[] out = new int[2]; // [jkeyCode, jkeyLocation]

            NSEvent.nsToJavaKeyInfo(in, out);

            jkeyCode = out[0];
            jkeyLocation = out[1];
            jeventType = isNpapiCallback ? NSEvent.npToJavaEventType(eventType) :
                                           NSEvent.nsToJavaEventType(eventType);
        }

        char javaChar = (testChar == KeyEvent.CHAR_UNDEFINED) ? KeyEvent.CHAR_UNDEFINED :
                NSEvent.nsToJavaChar(testChar, modifierFlags, spaceKeyTyped);

        int jmodifiers = NSEvent.nsToJavaModifiers(modifierFlags);
        long when = System.currentTimeMillis();

        if (jeventType == KeyEvent.KEY_PRESSED) {
            lastKeyPressCode = jkeyCode;
        }
        eventNotifier.notifyKeyEvent(jeventType, when, jmodifiers,
                jkeyCode, javaChar, jkeyLocation);

        // That's the reaction on the PRESSED (not RELEASED) event as it comes to
        // appear in MacOSX.
        // Modifier keys (shift, etc) don't want to send TYPED events.
        // On the other hand we don't want to generate keyTyped events
        // for clipboard related shortcuts like Meta + [CVX]
        if (jeventType == KeyEvent.KEY_PRESSED && needsKeyTyped && javaChar != KeyEvent.CHAR_UNDEFINED &&
                (jmodifiers & KeyEvent.META_DOWN_MASK) == 0) {
            if (actualChars == null) {
                // Either macOS didn't send us anything in insertText: to type,
                // or this event was not generated in AWTView.m. Let's fall back to using javaChar
                // since we still need to generate KEY_TYPED events, for instance for Ctrl+ combinations.
                // javaChar is guaranteed to be a valid character, since postsTyped is true.
                actualChars = String.valueOf(javaChar);
            }

            for (char ch : actualChars.toCharArray()) {
                eventNotifier.notifyKeyEvent(KeyEvent.KEY_TYPED, when, jmodifiers,
                        KeyEvent.VK_UNDEFINED, ch,
                        KeyEvent.KEY_LOCATION_UNKNOWN);
            }

            // If events come from Firefox, released events should also be generated.
            if (needsKeyReleased) {
                eventNotifier.notifyKeyEvent(KeyEvent.KEY_RELEASED, when, jmodifiers,
                        jkeyCode, javaChar,
                        KeyEvent.KEY_LOCATION_UNKNOWN);
            }
        }
    }

    void handleInputEvent(String text) {
        if (text != null) {
            int index = 0, length = text.length();
            char c = 0;
            while (index < length) {
                c = text.charAt(index);
                eventNotifier.notifyKeyEvent(KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        0, KeyEvent.VK_UNDEFINED, c,
                        KeyEvent.KEY_LOCATION_UNKNOWN);
                index++;
            }
            eventNotifier.notifyKeyEvent(KeyEvent.KEY_RELEASED,
                    System.currentTimeMillis(),
                    0, lastKeyPressCode, c,
                    KeyEvent.KEY_LOCATION_UNKNOWN);
        }
    }

    void handleWindowFocusEvent(boolean gained, LWWindowPeer opposite) {
        eventNotifier.notifyActivation(gained, opposite);
    }

    static class DeltaAccumulator {

        double accumulatedDelta;
        boolean accumulate;

        int getRoundedDelta(double delta, int scrollPhase) {

            int roundDelta = (int) Math.round(delta);

            if (scrollPhase == NSEvent.SCROLL_PHASE_UNSUPPORTED) { // mouse wheel
                if (roundDelta == 0 && delta != 0) {
                    roundDelta = delta > 0 ? 1 : -1;
                }
            } else { // trackpad
                if (scrollPhase == NSEvent.SCROLL_PHASE_BEGAN) {
                    accumulatedDelta = 0;
                    accumulate = true;
                }
                else if (scrollPhase == NSEvent.SCROLL_PHASE_MOMENTUM_BEGAN) {
                    accumulate = true;
                }
                if (accumulate) {

                    accumulatedDelta += delta;

                    roundDelta = (int) Math.round(accumulatedDelta);

                    accumulatedDelta -= roundDelta;

                    if (scrollPhase == NSEvent.SCROLL_PHASE_ENDED) {
                        accumulate = false;
                    }
                }
            }

            return roundDelta;
        }
    }
}
