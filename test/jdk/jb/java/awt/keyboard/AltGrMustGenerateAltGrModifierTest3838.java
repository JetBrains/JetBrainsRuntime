/*
 * Copyright 2021-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * @test
 * @summary Regression test for JBR-3838 ensures that pressing each layout-free key + AltGr produces AltGr modifier
 * @requires (os.family == "windows")
 * @key headful
 * @run main AltGrMustGenerateAltGrModifierTest3838
 * @author Nikita Provotorov
 */
public class AltGrMustGenerateAltGrModifierTest3838 extends Frame {

    public static void main(String[] args) throws Exception
    {
        final AltGrMustGenerateAltGrModifierTest3838 mainWindow = new AltGrMustGenerateAltGrModifierTest3838();

        try {
            mainWindow.setVisible(true);
            mainWindow.toFront();

            final Robot robot = new Robot();

            forceFocusTo(mainWindow.textArea, robot);

            Thread.sleep(PAUSE_MS);

            mainWindow.pressAllKeysWithAltGr(robot);

            Thread.sleep(PAUSE_MS);

            if (mainWindow.allKeysWerePressedAndReleased &&
                mainWindow.allModifiersAreCorrect        &&
                mainWindow.allModifiersExAreCorrect) {
                System.out.println("Test passed.");
            } else {
                throw new Exception("Test failed");
            }
        } finally {
            mainWindow.dispose();
        }
    }


    private AltGrMustGenerateAltGrModifierTest3838()
    {
        super("AltGr must generate AltGr modifier");

        setAlwaysOnTop(true);

        textArea = new TextArea();
        textArea.setFocusable(true);

        add(textArea);
        pack();
        setSize(250, 250);

        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e)
            {
                keyHandlerImpl("keyPressed", e, pressedKeyToCheck);
            }

            @Override
            public void keyReleased(KeyEvent e)
            {
                keyHandlerImpl("keyReleased", e, pressedKeyToCheck);
            }


            private void keyHandlerImpl(final String eventName, final KeyEvent e, final Integer expectedPressedKey)
            {
                if (expectedPressedKey == null) {
                    return;
                }

                try {
                    // Old API.

                    // Not all keyboard layouts have AltGr (e.g. ENG-US).
                    // On this keyboards right Alt produces only Alt without Ctrl.
                    final int modifiersOnMask = /*KeyEvent.CTRL_MASK |*/ KeyEvent.ALT_GRAPH_MASK | KeyEvent.ALT_MASK;
                    final int modifiers = e.getModifiers();
                    if ((modifiers & modifiersOnMask) != modifiersOnMask) {
                        allModifiersAreCorrect = false;
                        System.err.println(eventName + " {" + keyCodeToText(expectedPressedKey) + "}: wrong Modifiers;" +
                            " expected: " + modifiersToText(modifiersOnMask) +
                            ", actual: " + modifiersToText(modifiers) + ".");
                    }

                    // Modern API.

                    // Not all keyboard layouts have AltGr (e.g. ENG-US).
                    // On this keyboards right Alt produces only Alt without Ctrl.
                    final int modifiersExOnMask = /*KeyEvent.CTRL_DOWN_MASK |*/ KeyEvent.ALT_DOWN_MASK | KeyEvent.ALT_GRAPH_DOWN_MASK;
                    final int modifiersEx = e.getModifiersEx();
                    if ((modifiersEx & modifiersExOnMask) != modifiersExOnMask) {
                        allModifiersExAreCorrect = false;
                        System.err.println(eventName + " {" + keyCodeToText(expectedPressedKey) + "}: wrong ModifiersEx;" +
                            " expected: " + modifiersExToText(modifiersExOnMask) +
                            ", actual: " + modifiersExToText(modifiersEx) + ".");
                    }
                } finally {
                    keyPressedLatch.countDown();
                }
            }
        });
    }

    private void pressAllKeysWithAltGr(final Robot robot) throws Exception
    {
        for (final int keyToPress : allLayoutFreeVirtualKeys) {
            keyPressedLatch = new CountDownLatch(2);

            pressKeyWithAltGr(keyToPress, robot);

            if (!keyPressedLatch.await(PAUSE_MS, TimeUnit.MILLISECONDS)) {
                allKeysWerePressedAndReleased = false;
                System.err.println("Pressing {" + keyCodeToText(KeyEvent.VK_ALT_GRAPH) + " + " + keyCodeToText(keyToPress) + "}" +
                                   ": not all keys have been pressed or released.");
            }
        }
    }

    private void pressKeyWithAltGr(final int keyToPress, final Robot robot)
    {
        pressedKeyToCheck = null;

        robot.waitForIdle();

        robot.keyPress(KeyEvent.VK_ALT_GRAPH);
        robot.waitForIdle();

        try {
            robot.keyPress(pressedKeyToCheck = keyToPress);
            robot.keyRelease(keyToPress);

            robot.waitForIdle();

            pressedKeyToCheck = null;

            // restore lock-keys state
            if ( (keyToPress == KeyEvent.VK_CAPS_LOCK) ||
                 (keyToPress == KeyEvent.VK_NUM_LOCK)  ||
                 (keyToPress == KeyEvent.VK_KANA_LOCK) ||
                 (keyToPress == KeyEvent.VK_SCROLL_LOCK)  ) {
                robot.keyPress(keyToPress);
                robot.keyRelease(keyToPress);
            }
        } finally {
            robot.keyRelease(KeyEvent.VK_ALT_GRAPH);
            robot.waitForIdle();
        }
    }


    // Sometimes top-level Frame does not get focus when requestFocus is called.
    // For example, when this test is launched after test/.../bug6361367.java:
    //  jtreg test/jdk/javax/swing/text/JTextComponent/6361367/bug6361367.java test/jdk/jb/java/awt/keyboard/AltGrMustGenerateAltGrModifierTest3838.java
    //
    // So this method forces the focus acquiring via mouse clicking to the component.
    private static void forceFocusTo(final Component component, final Robot robot) {
        robot.waitForIdle();

        final Point componentTopLeft = component.getLocationOnScreen();
        final int componentCenterX = componentTopLeft.x + component.getWidth() / 2;
        final int componentCenterY = componentTopLeft.y + component.getHeight() / 2;

        robot.mouseMove(componentCenterX, componentCenterY);
        robot.waitForIdle();

        robot.mousePress(InputEvent.BUTTON1_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_MASK);

        robot.waitForIdle();

        component.requestFocus();

        robot.waitForIdle();
    }

    private static String keyCodeToText(int keyCode) {
        return "<" + KeyEvent.getKeyText(keyCode) + " (code=" + keyCode + ")>";
    }

    private static String modifiersToText(int modifiers) {
        return "[" + KeyEvent.getKeyModifiersText(modifiers) + " (code=" + modifiers + ")]";
    }

    private static String modifiersExToText(int modifiersEx) {
        return "[" + KeyEvent.getModifiersExText(modifiersEx) + " (code=" + modifiersEx + ")]";
    }

    private static final int PAUSE_MS = 2000;


    private final TextArea textArea;

    private volatile boolean allKeysWerePressedAndReleased = true;
    private volatile boolean allModifiersAreCorrect = true;
    private volatile boolean allModifiersExAreCorrect = true;

    private volatile Integer pressedKeyToCheck = null;
    private volatile CountDownLatch keyPressedLatch = null;

    private final int[] allLayoutFreeVirtualKeys = new int[] {
        // Modifier keys

        KeyEvent.VK_CAPS_LOCK,
        KeyEvent.VK_SHIFT,
        /* VK_CONTROL releasing removes SHIFT_MASK, SHIFT_DOWN_MASK - notepad.exe, VSCode have the same behavior */
        //KeyEvent.VK_CONTROL,
        KeyEvent.VK_ALT,
        //KeyEvent.VK_ALT_GRAPH,
        KeyEvent.VK_NUM_LOCK,

        // Miscellaneous Windows keys

        KeyEvent.VK_WINDOWS,
        KeyEvent.VK_WINDOWS,
        KeyEvent.VK_CONTEXT_MENU,

        // Alphabet

        KeyEvent.VK_A,
        KeyEvent.VK_B,
        KeyEvent.VK_C,
        KeyEvent.VK_D,
        KeyEvent.VK_E,
        KeyEvent.VK_F,
        KeyEvent.VK_G,
        KeyEvent.VK_H,
        KeyEvent.VK_I,
        KeyEvent.VK_J,
        KeyEvent.VK_K,
        KeyEvent.VK_L,
        KeyEvent.VK_M,
        KeyEvent.VK_N,
        KeyEvent.VK_O,
        KeyEvent.VK_P,
        KeyEvent.VK_Q,
        KeyEvent.VK_R,
        KeyEvent.VK_S,
        KeyEvent.VK_T,
        KeyEvent.VK_U,
        KeyEvent.VK_V,
        KeyEvent.VK_W,
        KeyEvent.VK_X,
        KeyEvent.VK_Y,
        KeyEvent.VK_Z,

        // Standard numeric row

        KeyEvent.VK_0,
        KeyEvent.VK_1,
        KeyEvent.VK_2,
        KeyEvent.VK_3,
        KeyEvent.VK_4,
        KeyEvent.VK_5,
        KeyEvent.VK_6,
        KeyEvent.VK_7,
        KeyEvent.VK_8,
        KeyEvent.VK_9,

        // Misc key from main block

        KeyEvent.VK_ENTER,
        /* avoid SC_KEYMENU event on layouts which don't have AltGr */
        //KeyEvent.VK_SPACE,
        KeyEvent.VK_BACK_SPACE,
        /* avoid focus switching */
        //KeyEvent.VK_TAB,
        //KeyEvent.VK_ESCAPE,

        // NumPad with NumLock off & extended block (rectangular)

        KeyEvent.VK_INSERT,
        KeyEvent.VK_DELETE,
        KeyEvent.VK_HOME,
        KeyEvent.VK_END,
        KeyEvent.VK_PAGE_UP,
        KeyEvent.VK_PAGE_DOWN,
        KeyEvent.VK_CLEAR,

        // NumPad with NumLock off & extended arrows block (triangular)

        KeyEvent.VK_LEFT,
        KeyEvent.VK_RIGHT,
        KeyEvent.VK_UP,
        KeyEvent.VK_DOWN,

        // NumPad with NumLock on: numbers

        KeyEvent.VK_NUMPAD0,
        KeyEvent.VK_NUMPAD1,
        KeyEvent.VK_NUMPAD2,
        KeyEvent.VK_NUMPAD3,
        KeyEvent.VK_NUMPAD4,
        KeyEvent.VK_NUMPAD5,
        KeyEvent.VK_NUMPAD6,
        KeyEvent.VK_NUMPAD7,
        KeyEvent.VK_NUMPAD8,
        KeyEvent.VK_NUMPAD9,

        // NumPad with NumLock on

        KeyEvent.VK_MULTIPLY,
        KeyEvent.VK_ADD,
        KeyEvent.VK_SEPARATOR,
        KeyEvent.VK_SUBTRACT,
        KeyEvent.VK_DECIMAL,
        KeyEvent.VK_DIVIDE,

        // Functional keys

        KeyEvent.VK_F1,
        KeyEvent.VK_F2,
        KeyEvent.VK_F3,
        KeyEvent.VK_F4,
        KeyEvent.VK_F5,
        KeyEvent.VK_F6,
        KeyEvent.VK_F7,
        KeyEvent.VK_F8,
        KeyEvent.VK_F9,
        KeyEvent.VK_F10,
        KeyEvent.VK_F11,
        KeyEvent.VK_F12,
        KeyEvent.VK_F13,
        KeyEvent.VK_F14,
        KeyEvent.VK_F15,
        KeyEvent.VK_F16,
        KeyEvent.VK_F17,
        KeyEvent.VK_F18,
        KeyEvent.VK_F19,
        KeyEvent.VK_F20,
        KeyEvent.VK_F21,
        KeyEvent.VK_F22,
        KeyEvent.VK_F23,
        KeyEvent.VK_F24,

        /* Windows does not produce WM_KEYDOWN for VK_SNAPSHOT; see JDK-4455060 */
        //KeyEvent.VK_PRINTSCREEN,
        KeyEvent.VK_SCROLL_LOCK,
        KeyEvent.VK_PAUSE,
        KeyEvent.VK_CANCEL,
        KeyEvent.VK_HELP,

        // Japanese

        /* -> [VK_ALT] + VK_CONVERT -> [VK_ALT] + VK_ALL_CANDIDATES */
        KeyEvent.VK_CONVERT,
        KeyEvent.VK_NONCONVERT,
        KeyEvent.VK_INPUT_METHOD_ON_OFF,
        /* -> [VK_ALT] + VK_ALPHANUMERIC -> [VK_ALT] + VK_CODE_INPUT */
        KeyEvent.VK_ALPHANUMERIC,
        KeyEvent.VK_KATAKANA,
        KeyEvent.VK_HIRAGANA,
        KeyEvent.VK_FULL_WIDTH,
        KeyEvent.VK_HALF_WIDTH,
        KeyEvent.VK_ROMAN_CHARACTERS,

        KeyEvent.VK_ALL_CANDIDATES,
        /* [VK_ALT] + VK_PREVIOUS_CANDIDATE -> [VK_ALT + VK_SHIFT] + VK_CONVERT -> [VK_ALT + VK_SHIFT] + VK_ALL_CANDIDATES */
        KeyEvent.VK_PREVIOUS_CANDIDATE,
        KeyEvent.VK_CODE_INPUT,
        /* can only be found if is available */
        //KeyEvent.VK_KANA_LOCK,
    };
}
