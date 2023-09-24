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

import sun.awt.wl.WLRobotPeer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;

import static java.awt.event.KeyEvent.*;

/**
 * @test
 * @key headful
 * @summary JBR-5676 Wayland: support generation of input events by AWT Robot in weston plugin
 * @modules java.desktop/sun.awt.wl
 * @requires (os.family == "linux")
 * @library /test/lib
 * @build RobotKeyboard
 * @run driver WakefieldTestDriver -timeout 180 RobotKeyboard
 */

public class RobotKeyboard {
    private record KeySymDescriptor(String value, boolean isDead) {}
    private record KeyDescriptor (
            KeySymDescriptor level1,
            KeySymDescriptor level2,
            KeySymDescriptor level3,
            KeySymDescriptor level4) {
        public KeySymDescriptor byLevel(int level) {
            switch (level) {
                case 0: return level1;
                case 1: return level2;
                case 2: return level3;
                case 3: return level4;
            }
            throw new IllegalArgumentException("level");
        }
    }

    private record Compose(KeySymDescriptor dead, String key, String result) {}

    private static class Keys {
        private static KeyDescriptor key(Object level1, Object level2, Object level3, Object level4) {
            if (level1 instanceof String) level1 = new KeySymDescriptor((String)level1, false);
            if (level2 instanceof String) level2 = new KeySymDescriptor((String)level2, false);
            if (level3 instanceof String) level3 = new KeySymDescriptor((String)level3, false);
            if (level4 instanceof String) level4 = new KeySymDescriptor((String)level4, false);
            return new KeyDescriptor((KeySymDescriptor) level1, (KeySymDescriptor) level2, (KeySymDescriptor) level3, (KeySymDescriptor) level4);
        }

        private static KeySymDescriptor dead(String value) {
            return new KeySymDescriptor(value, true);
        }

        final static KeySymDescriptor DEAD_ABOVERING = dead("°");
        final static KeySymDescriptor DEAD_DOUBLEACUTE = dead("˝");
        final static KeySymDescriptor DEAD_MACRON = dead("¯");
        final static KeySymDescriptor DEAD_CEDILLA = dead("¸");
        final static KeySymDescriptor DEAD_CIRCUMFLEX = dead("^");
        final static KeySymDescriptor DEAD_HORN = dead("\u031b");
        final static KeySymDescriptor DEAD_OGONEK = dead("˛");
        final static KeySymDescriptor DEAD_BREVE = dead("˘");
        final static KeySymDescriptor DEAD_ABOVEDOT = dead("˙");
        final static KeySymDescriptor DEAD_CARON = dead("ˇ");
        final static KeySymDescriptor DEAD_HOOK = dead("\u0309");
        final static KeySymDescriptor DEAD_BELOWDOT = dead("\u0323");
        final static KeySymDescriptor DEAD_ACUTE = dead("'");
        final static KeySymDescriptor DEAD_DIAERESIS = dead("\"");
        final static KeySymDescriptor DEAD_GRAVE = dead("`");
        final static KeySymDescriptor DEAD_TILDE = dead("~");

        final static KeyDescriptor VK_0 = key("0", ")", "’", DEAD_ABOVERING);
        final static KeyDescriptor VK_1 = key("1", "!", "¹", "¡");
        final static KeyDescriptor VK_2 = key("2", "@", "²", DEAD_DOUBLEACUTE);
        final static KeyDescriptor VK_3 = key("3", "#", "³", DEAD_MACRON);
        final static KeyDescriptor VK_4 = key("4", "$", "¤", "£");
        final static KeyDescriptor VK_5 = key("5", "%", "€", DEAD_CEDILLA);
        final static KeyDescriptor VK_6 = key("6", "^", DEAD_CIRCUMFLEX, "¼");
        final static KeyDescriptor VK_7 = key("7", "&", DEAD_HORN, "½");
        final static KeyDescriptor VK_8 = key("8", "*", DEAD_OGONEK, "¾");
        final static KeyDescriptor VK_9 = key("9", "(", "‘", DEAD_BREVE);
        final static KeyDescriptor VK_A = key("a", "A", "á", "Á");
        final static KeyDescriptor VK_B = key("b", "B", "b", "B");
        final static KeyDescriptor VK_C = key("c", "C", "©", "¢");
        final static KeyDescriptor VK_D = key("d", "D", "ð", "Ð");
        final static KeyDescriptor VK_E = key("e", "E", "é", "É");
        final static KeyDescriptor VK_F = key("f", "F", "f", "F");
        final static KeyDescriptor VK_G = key("g", "G", "g", "G");
        final static KeyDescriptor VK_H = key("h", "H", "h", "H");
        final static KeyDescriptor VK_I = key("i", "I", "í", "Í");
        final static KeyDescriptor VK_J = key("j", "J", "ï", "Ï");
        final static KeyDescriptor VK_K = key("k", "K", "œ", "Œ");
        final static KeyDescriptor VK_L = key("l", "L", "ø", "Ø");
        final static KeyDescriptor VK_M = key("m", "M", "µ", "µ");
        final static KeyDescriptor VK_N = key("n", "N", "ñ", "Ñ");
        final static KeyDescriptor VK_O = key("o", "O", "ó", "Ó");
        final static KeyDescriptor VK_P = key("p", "P", "ö", "Ö");
        final static KeyDescriptor VK_Q = key("q", "Q", "ä", "Ä");
        final static KeyDescriptor VK_R = key("r", "R", "ë", "Ë");
        final static KeyDescriptor VK_S = key("s", "S", "ß", "§");
        final static KeyDescriptor VK_T = key("t", "T", "þ", "Þ");
        final static KeyDescriptor VK_U = key("u", "U", "ú", "Ú");
        final static KeyDescriptor VK_V = key("v", "V", "®", "™");
        final static KeyDescriptor VK_W = key("w", "W", "å", "Å");
        final static KeyDescriptor VK_X = key("x", "X", "œ", "Œ");
        final static KeyDescriptor VK_Y = key("y", "Y", "ü", "Ü");
        final static KeyDescriptor VK_Z = key("z", "Z", "æ", "Æ");
        final static KeyDescriptor VK_COMMA = key(",", "<", "ç", "Ç");
        final static KeyDescriptor VK_PERIOD = key(".", ">", DEAD_ABOVEDOT, DEAD_CARON);
        final static KeyDescriptor VK_SLASH = key("/", "?", "¿", DEAD_HOOK);
        final static KeyDescriptor VK_LESS = key("\\", "|", "\\", "|"); // Key to the right of LShift on ISO keyboards
        final static KeyDescriptor VK_MINUS = key("-", "_", "¥", DEAD_BELOWDOT);
        final static KeyDescriptor VK_EQUALS = key("=", "+", "×", "÷");
        final static KeyDescriptor VK_OPEN_BRACKET = key("[", "{", "«", "“");
        final static KeyDescriptor VK_CLOSE_BRACKET = key("]", "}", "»", "”");
        final static KeyDescriptor VK_SEMICOLON = key(";", ":", "¶", "°");
        final static KeyDescriptor VK_QUOTE = key("'", "\"", DEAD_ACUTE, DEAD_DIAERESIS);
        final static KeyDescriptor VK_BACK_QUOTE = key("`", "~", DEAD_GRAVE, DEAD_TILDE);
        final static KeyDescriptor VK_BACK_SLASH = key("\\", "|", "¬", "¦");

        final static Compose[] composeSamples = new Compose[] {
                new Compose(DEAD_ABOVERING, "a", "å"),
                new Compose(DEAD_ABOVERING, "A", "Å"),
                new Compose(DEAD_ABOVERING, "b", null),
                new Compose(DEAD_DOUBLEACUTE, "o", "ő"),
                new Compose(DEAD_DOUBLEACUTE, "O", "Ő"),
                new Compose(DEAD_DOUBLEACUTE, "a", null),
                new Compose(DEAD_MACRON, "a", "ā"),
                new Compose(DEAD_MACRON, "A", "Ā"),
                new Compose(DEAD_MACRON, "b", null),
                new Compose(DEAD_CEDILLA, "c", "ç"),
                new Compose(DEAD_CEDILLA, "C", "Ç"),
                new Compose(DEAD_CEDILLA, "a", null),
                new Compose(DEAD_CIRCUMFLEX, "a", "â"),
                new Compose(DEAD_CIRCUMFLEX, "A", "Â"),
                new Compose(DEAD_CIRCUMFLEX, "b", null),
                new Compose(DEAD_HORN, "u", "ư"),
                new Compose(DEAD_HORN, "U", "Ư"),
                new Compose(DEAD_HORN, "a", null),
                new Compose(DEAD_OGONEK, "a", "ą"),
                new Compose(DEAD_OGONEK, "A", "Ą"),
                new Compose(DEAD_OGONEK, "b", null),
                new Compose(DEAD_BREVE, "a", "ă"),
                new Compose(DEAD_BREVE, "A", "Ă"),
                new Compose(DEAD_BREVE, "b", null),
                new Compose(DEAD_ABOVEDOT, "a", "ȧ"),
                new Compose(DEAD_ABOVEDOT, "A", "Ȧ"),
                new Compose(DEAD_ABOVEDOT, "u", null),
                new Compose(DEAD_CARON, "a", "ǎ"),
                new Compose(DEAD_CARON, "A", "Ǎ"),
                new Compose(DEAD_CARON, "b", null),
                new Compose(DEAD_HOOK, "a", "ả"),
                new Compose(DEAD_HOOK, "A", "Ả"),
                new Compose(DEAD_HOOK, ";", null),
                new Compose(DEAD_BELOWDOT, "a", "ạ"),
                new Compose(DEAD_BELOWDOT, "A", "Ạ"),
                new Compose(DEAD_BELOWDOT, "c", null),
                new Compose(DEAD_ACUTE, "a", "á"),
                new Compose(DEAD_ACUTE, "A", "Á"),
                new Compose(DEAD_ACUTE, "b", null),
                new Compose(DEAD_DIAERESIS, "a", "ä"),
                new Compose(DEAD_DIAERESIS, "A", "Ä"),
                new Compose(DEAD_DIAERESIS, "b", null),
                new Compose(DEAD_GRAVE, "a", "à"),
                new Compose(DEAD_GRAVE, "A", "À"),
                new Compose(DEAD_GRAVE, "b", null),
                new Compose(DEAD_TILDE, "a", "ã"),
                new Compose(DEAD_TILDE, "A", "Ã"),
                new Compose(DEAD_TILDE, "b", null),
        };
    }

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
            "VK_F13",
            "VK_F14",
            "VK_F15",
            "VK_F16",
            "VK_F17",
            "VK_F18",
            "VK_F19",
            "VK_F20",
            "VK_F21",
            "VK_F22",
            "VK_F23",
            "VK_F24",
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
// TODO: WLToolkit reports VK_STOP as VK_CANCEL
//            "VK_STOP",
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
    private static JTextArea infoTextArea;
    private static JTextArea eventsTextArea;
    private static JTextArea typedTextArea;
    private static final List<KeyEvent> events = new ArrayList<>();
    private static String typed = "";
    private static final StringBuffer log = new StringBuffer();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Robot Keyboard Test");

            frame.setLayout(new FlowLayout());

            infoTextArea = new JTextArea("", 30, 30);
            infoTextArea.setEditable(false);
            frame.add(new JScrollPane(infoTextArea));
            eventsTextArea = new JTextArea("", 30, 30);
            eventsTextArea.setEditable(false);
            frame.add(new JScrollPane(eventsTextArea));
            typedTextArea = new JTextArea("", 30, 30);
            frame.add(new JScrollPane(typedTextArea));

            frame.pack();
            frame.setVisible(true);

            typedTextArea.addKeyListener(
                    new KeyListener() {
                        @Override
                        public void keyTyped(KeyEvent e) {
                            typed = typed + e.getKeyChar();
                        }

                        @Override
                        public void keyPressed(KeyEvent e) {
                            eventsTextArea.append("press: " + getKeyText(e.getKeyCode()) + "\n");
                            eventsTextArea.setCaretPosition(eventsTextArea.getDocument().getLength());
                            events.add(e);
                        }

                        @Override
                        public void keyReleased(KeyEvent e) {
                            eventsTextArea.append("release: " + getKeyText(e.getKeyCode()) + "\n");
                            eventsTextArea.setCaretPosition(eventsTextArea.getDocument().getLength());
                            events.add(e);
                        }
                    }
            );
        });

        robot = new Robot();
        robot.setAutoDelay(50);
        typedTextArea.requestFocusInWindow();
        robot.delay(100);
        WLRobotPeer.setXKBLayout("us", "altgr-intl", "");
        robot.delay(100);

        if (Toolkit.getDefaultToolkit().getLockingKeyState(VK_CAPS_LOCK)) {
            // Disable caps lock
            robot.keyPress(VK_CAPS_LOCK);
            robot.keyRelease(VK_CAPS_LOCK);
        }

        if (!Toolkit.getDefaultToolkit().getLockingKeyState(VK_NUM_LOCK)) {
            // Enable num lock
            robot.keyPress(VK_NUM_LOCK);
            robot.keyRelease(VK_NUM_LOCK);
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
        System.err.println(log.toString());

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

    private static KeyDescriptor getKeyDescriptor(String name) {
        try {
            return (KeyDescriptor) Keys.class.getDeclaredField(name).get(Keys.class);
        } catch (NoSuchFieldException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void typeChar(String what) {
        try {
            for (var field : Keys.class.getDeclaredFields()) {
                if (field.getName().startsWith("VK_")) {
                    var keyCode = getKeyCodeByName(field.getName());
                    var desc = (KeyDescriptor) field.get(Keys.class);
                    for (int level = 0; level < 4; ++level) {
                        var sym = desc.byLevel(level);
                        if (!sym.isDead && sym.value.equals(what)) {
                            if (level == 1 || level == 3) {
                                robot.keyPress(VK_SHIFT);
                            }
                            if (level == 2 || level == 3) {
                                robot.keyPress(VK_ALT_GRAPH);
                            }
                            robot.keyPress(keyCode);
                            robot.keyRelease(keyCode);
                            if (level == 1 || level == 3) {
                                robot.keyRelease(VK_SHIFT);
                            }
                            if (level == 2 || level == 3) {
                                robot.keyRelease(VK_ALT_GRAPH);
                            }
                            robot.waitForIdle();
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void checkKey(int keyCode, int level, KeyDescriptor desc, Compose compose) {
        events.clear();
        typedTextArea.requestFocusInWindow();
        typed = "";
        robot.waitForIdle();
        int mods = 0;
        if (level == 1 || level == 3) {
            mods |= SHIFT_DOWN_MASK;
            robot.keyPress(VK_SHIFT);
            log("Shift + ");
        }
        if (level == 2 || level == 3) {
            mods |= ALT_GRAPH_DOWN_MASK;
            robot.keyPress(VK_ALT_GRAPH);
            log("AltGr + ");
        }
        log(getKeyText(keyCode));
        if (compose != null) {
            log(", compose ");
            log(compose.key);
        }
        log(": ");
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        if (level == 1 || level == 3) {
            robot.keyRelease(VK_SHIFT);
        }
        if (level == 2 || level == 3) {
            robot.keyRelease(VK_ALT_GRAPH);
        }
        robot.waitForIdle();

        List<KeyEvent> cleanEvents = new ArrayList<>();

        for (KeyEvent e : events) {
            if (e.getKeyCode() == VK_SHIFT && ((mods & SHIFT_DOWN_MASK) != 0)) continue;
            if (e.getKeyCode() == VK_ALT_GRAPH && ((mods & ALT_GRAPH_DOWN_MASK) != 0)) continue;
            cleanEvents.add(e);
        }

        if (cleanEvents.size() != 2) {
            throw new RuntimeException("Expected two events, got: " + events.size());
        }

        if (cleanEvents.get(0).getID() != KeyEvent.KEY_PRESSED || cleanEvents.get(1).getID() != KeyEvent.KEY_RELEASED) {
            throw new RuntimeException("Expected one KEY_PRESSED and one KEY_RELEASED");
        }

        var expectedKeyCode = desc != null ? getExtendedKeyCodeForChar(desc.level1.value.codePointAt(0)) : keyCode;

        if (cleanEvents.get(0).getKeyCode() != expectedKeyCode) {
            throw new RuntimeException("KEY_PRESSED keyCode = " + cleanEvents.get(0).getKeyCode() + ", expected " + expectedKeyCode);
        }

        if (cleanEvents.get(1).getKeyCode() != expectedKeyCode) {
            throw new RuntimeException("KEY_RELEASED keyCode = " + cleanEvents.get(1).getKeyCode() + ", expected " + expectedKeyCode);
        }

//        if (cleanEvents.get(0).getModifiersEx() != mods) {
//            throw new RuntimeException("KEY_PRESSED mods = " + cleanEvents.get(0).getModifiersEx() + ", expected " + mods);
//        }
//
//        if (cleanEvents.get(1).getModifiersEx() != mods) {
//            throw new RuntimeException("KEY_RELEASED mods = " + cleanEvents.get(1).getModifiersEx() + ", expected " + mods);
//        }

        var sym = desc == null ? null : desc.byLevel(level);

        if (sym != null) {
            String value = sym.value;
            if (sym.isDead) {
                if (compose == null) {
                    robot.keyPress(VK_SPACE);
                    robot.keyRelease(VK_SPACE);
                    robot.waitForIdle();
                } else {
                    typeChar(compose.key);
                    value = compose.result == null ? "" : compose.result;
                }
            }

            if (!typed.equals(value)) {
                throw new RuntimeException("KEY_TYPED: expected '" + sym.value + "', got '" + typed + "'");
            }
        }
    }

    private static void log(String what) {
        infoTextArea.append(what);
        infoTextArea.setCaretPosition(infoTextArea.getDocument().getLength());
        log.append(what);
    }

    private static boolean processKey(String name) {
        var keyCode = getKeyCodeByName(name);
        var desc = getKeyDescriptor(name);
        int maxLevel = desc == null ? 1 : 4;
        boolean ok = true;
        for (int level = 0; level < maxLevel; ++level) {
            typedTextArea.append(getKeyText(keyCode) + ", level " + level + ": ");
            typedTextArea.setCaretPosition(typedTextArea.getDocument().getLength());
            var sym = desc == null ? null : desc.byLevel(level);
            List<Compose> composes = new ArrayList<>();
            composes.add(null);
            if (sym != null && sym.isDead) {
                for (Compose c : Keys.composeSamples) {
                    if (c.dead == sym) {
                        composes.add(c);
                    }
                }
            }

            for (Compose compose : composes) {
                try {
                    checkKey(keyCode, level, desc, compose);
                    log("OK\n");
                } catch (RuntimeException e) {
                    log(e.getMessage());
                    log("\n");
                    ok = false;
                }
            }

            typedTextArea.append("\n");
            typedTextArea.setCaretPosition(typedTextArea.getDocument().getLength());
        }
        return ok;
    }
}