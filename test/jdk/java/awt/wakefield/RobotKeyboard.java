/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * @test
 * @key headful
 * @summary JBR-5676 Wayland: support generation of input events by AWT Robot in weston plugin
 * @requires (os.family == "linux")
 * @library /test/lib
 * @build RobotKeyboard
 * @run driver WakefieldTestDriver -timeout 60 RobotKeyboard
 */

public class RobotKeyboard {
    private static String ordinaryKeyNames[] = {
            "VK_0",
            "VK_1",
            "VK_2",
            "VK_3",
            "VK_4",
            "VK_5",
            "VK_6",
            "VK_7",
            "VK_8",
            "VK_9",
            "VK_A",
            "VK_ADD",
            "VK_AGAIN",
            "VK_ALT",
// TODO: WLToolkit doesn't differentiate VK_ALT and VK_ALT_GRAPH for now
//            "VK_ALT_GRAPH",
            "VK_B",
            "VK_BACK_QUOTE",
            "VK_BACK_SLASH",
            "VK_BACK_SPACE",
            "VK_C",
            "VK_CLOSE_BRACKET",
            "VK_COMMA",
            "VK_CONTROL",
            "VK_D",
            "VK_DECIMAL",
            "VK_DELETE",
            "VK_DIVIDE",
            "VK_DOWN",
            "VK_E",
            "VK_END",
            "VK_ENTER",
            "VK_EQUALS",
            "VK_ESCAPE",
            "VK_F",
            "VK_F1",
            "VK_F2",
            "VK_F3",
            "VK_F4",
            "VK_F5",
            "VK_F6",
            "VK_F7",
            "VK_F8",
            "VK_F9",
            "VK_F10",
            "VK_F11",
            "VK_F12",
// TODO: WLToolkit ignores F13..F24 due to the XKB issues presumably
//            "VK_F13",
//            "VK_F14",
//            "VK_F15",
//            "VK_F16",
//            "VK_F17",
//            "VK_F18",
//            "VK_F19",
//            "VK_F20",
//            "VK_F21",
//            "VK_F22",
//            "VK_F23",
//            "VK_F24",
            "VK_FIND",
            "VK_G",
            "VK_H",
            "VK_HELP",
            "VK_HIRAGANA",
            "VK_HOME",
            "VK_I",
            "VK_INPUT_METHOD_ON_OFF",
            "VK_INSERT",
            "VK_J",
            "VK_K",
            "VK_KATAKANA",
            "VK_L",
            "VK_LEFT",
            "VK_LEFT_PARENTHESIS",
            "VK_LESS",
            "VK_M",
// TODO: WLToolkit reports the Meta key as VK_WINDOWS
//            "VK_META",
            "VK_MINUS",
            "VK_MULTIPLY",
            "VK_N",
            "VK_NONCONVERT",
            "VK_NUMPAD0",
            "VK_NUMPAD1",
            "VK_NUMPAD2",
            "VK_NUMPAD3",
            "VK_NUMPAD4",
            "VK_NUMPAD5",
            "VK_NUMPAD6",
            "VK_NUMPAD7",
            "VK_NUMPAD8",
            "VK_NUMPAD9",
            "VK_O",
            "VK_OPEN_BRACKET",
            "VK_P",
            "VK_PAGE_DOWN",
            "VK_PAGE_UP",
            "VK_PAUSE",
            "VK_PERIOD",
            "VK_PRINTSCREEN",
            "VK_Q",
            "VK_QUOTE",
            "VK_R",
            "VK_RIGHT",
            "VK_RIGHT_PARENTHESIS",
            "VK_S",
            "VK_SEMICOLON",
            "VK_SHIFT",
            "VK_SLASH",
            "VK_SPACE",
            "VK_STOP",
            "VK_SUBTRACT",
            "VK_T",
            "VK_TAB",
            "VK_U",
            "VK_UNDO",
            "VK_UP",
            "VK_V",
            "VK_W",
            "VK_WINDOWS",
            "VK_X",
            "VK_Y",
            "VK_Z",
    };

    private static String lockingKeyNames[] = {
            "VK_CAPS_LOCK",
            "VK_NUM_LOCK",
            "VK_SCROLL_LOCK",
    };

    private static Robot robot;
    private static JFrame frame;
    private static JTextArea textArea;
    private static final List<KeyEvent> events = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("test");

            textArea = new JTextArea("");
            textArea.setEditable(false);
            frame.add(new JScrollPane(textArea));
            frame.setSize(500, 500);
            frame.setVisible(true);

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(
                    new KeyEventDispatcher() {
                        @Override
                        public boolean dispatchKeyEvent(KeyEvent e) {
                            if (e.getID() == KeyEvent.KEY_PRESSED || e.getID() == KeyEvent.KEY_RELEASED) {
                                events.add(e);
                            }
                            return false;
                        }
                    }
            );
        });

        robot = new Robot();
        robot.setAutoDelay(50);
        robot.delay(500);

        if (Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK)) {
            // Disable caps lock
            robot.keyPress(KeyEvent.VK_CAPS_LOCK);
            robot.keyRelease(KeyEvent.VK_CAPS_LOCK);
        }

        if (!Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_NUM_LOCK)) {
            // Enable num lock
            robot.keyPress(KeyEvent.VK_NUM_LOCK);
            robot.keyRelease(KeyEvent.VK_NUM_LOCK);
        }

        boolean ok = true;

        for (String key : ordinaryKeyNames) {
            ok &= processKey(key);
        }

        for (String key : lockingKeyNames) {
            ok &= processKey(key);

            // reset the locking state to the previous one
            int keyCode = getKeyCodeByName(key);
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            robot.waitForIdle();
        }

        System.err.println("===== TEST RESULT =====");
        System.err.println(ok ? "TEST PASSED" : "TEST FAILED");
        System.err.println("===== FULL LOG =====");
        System.err.println(textArea.getText());

        frame.dispose();

        // Due to some reason that probably has something to do with the implementation
        // of the test driver, it's necessary to manually call System.exit() here
        System.exit(ok ? 0 : 1);
    }

    private static int getKeyCodeByName(String name) {
        try {
            return KeyEvent.class.getDeclaredField(name).getInt(KeyEvent.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkKey(String name) {
        int keyCode = getKeyCodeByName(name);
        events.clear();
        textArea.grabFocus();
        robot.waitForIdle();
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        robot.waitForIdle();

        if (events.size() != 2) {
            throw new RuntimeException("Expected two events, got: " + events.size());
        }

        if (events.get(0).getID() != KeyEvent.KEY_PRESSED || events.get(1).getID() != KeyEvent.KEY_RELEASED) {
            throw new RuntimeException("Expected one KEY_PRESSED and one KEY_RELEASED");
        }

        if (events.get(0).getKeyCode() != keyCode) {
            throw new RuntimeException("KEY_PRESSED keyCode is " + events.get(0).getKeyCode() + ", expected " + keyCode);
        }

        if (events.get(1).getKeyCode() != keyCode) {
            throw new RuntimeException("KEY_RELEASED keyCode is " + events.get(1).getKeyCode() + ", expected " + keyCode);
        }
    }

    private static void log(String what) {
        textArea.append(what);
        textArea.setCaretPosition(textArea.getDocument().getLength());
        System.err.print(what);
    }

    private static boolean processKey(String name) {
        log(name + ": ");
        try {
            checkKey(name);
            log("OK\n");
            return true;
        } catch (RuntimeException e) {
            log(e.getMessage() + "\n");
            return false;
        }
    }
}