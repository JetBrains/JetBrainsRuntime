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
 * @run driver/timeout=600 WakefieldTestDriver -timeout 600 RobotKeyboard
 */

public class RobotKeyboard {
    private record Compose(String dead, String key, String result) {
    }

    private static class Keys {
        final static String DEAD_ABOVERING = "°";
        final static String DEAD_DOUBLEACUTE = "˝";
        final static String DEAD_MACRON = "¯";
        final static String DEAD_CEDILLA = "¸";
        final static String DEAD_CIRCUMFLEX = "^";
        final static String DEAD_HORN = "\u031b";
        final static String DEAD_OGONEK = "˛";
        final static String DEAD_BREVE = "˘";
        final static String DEAD_ABOVEDOT = "˙";
        final static String DEAD_CARON = "ˇ";
        final static String DEAD_HOOK = "\u0309";
        final static String DEAD_BELOWDOT = "\u0323";
        final static String DEAD_ACUTE = "'";
        final static String DEAD_DIAERESIS = "\"";
        final static String DEAD_GRAVE = "`";
        final static String DEAD_TILDE = "~";
        final static String DEAD_BELOWMACRON = "\u0331";


        final static Compose[] composeSamples = new Compose[]{
                new Compose(DEAD_ABOVERING, " ", DEAD_ABOVERING),
                new Compose(DEAD_ABOVERING, "a", "å"),
                new Compose(DEAD_ABOVERING, "A", "Å"),
                new Compose(DEAD_ABOVERING, "b", null),
                new Compose(DEAD_DOUBLEACUTE, " ", DEAD_DOUBLEACUTE),
                new Compose(DEAD_DOUBLEACUTE, "o", "ő"),
                new Compose(DEAD_DOUBLEACUTE, "O", "Ő"),
                new Compose(DEAD_DOUBLEACUTE, "a", null),
                new Compose(DEAD_MACRON, " ", DEAD_MACRON),
                new Compose(DEAD_MACRON, "a", "ā"),
                new Compose(DEAD_MACRON, "A", "Ā"),
                new Compose(DEAD_MACRON, "и", "ӣ"),
                new Compose(DEAD_MACRON, "И", "Ӣ"),
                new Compose(DEAD_MACRON, "b", null),
                new Compose(DEAD_MACRON, "ы", null),
                new Compose(DEAD_CEDILLA, " ", DEAD_CEDILLA),
                new Compose(DEAD_CEDILLA, "c", "ç"),
                new Compose(DEAD_CEDILLA, "C", "Ç"),
                new Compose(DEAD_CEDILLA, "a", null),
                new Compose(DEAD_CIRCUMFLEX, " ", DEAD_CIRCUMFLEX),
                new Compose(DEAD_CIRCUMFLEX, "a", "â"),
                new Compose(DEAD_CIRCUMFLEX, "A", "Â"),
                new Compose(DEAD_CIRCUMFLEX, "3", "³"),
                new Compose(DEAD_CIRCUMFLEX, "и", "и̂"),
                new Compose(DEAD_CIRCUMFLEX, "И", "И̂"),
                new Compose(DEAD_CIRCUMFLEX, "b", null),
                new Compose(DEAD_CIRCUMFLEX, "ы", null),
                new Compose(DEAD_HORN, " ", DEAD_HORN),
                new Compose(DEAD_HORN, "u", "ư"),
                new Compose(DEAD_HORN, "U", "Ư"),
                new Compose(DEAD_HORN, "a", null),
                new Compose(DEAD_OGONEK, " ", DEAD_OGONEK),
                new Compose(DEAD_OGONEK, "a", "ą"),
                new Compose(DEAD_OGONEK, "A", "Ą"),
                new Compose(DEAD_OGONEK, "b", null),
                new Compose(DEAD_BREVE, " ", DEAD_BREVE),
                new Compose(DEAD_BREVE, "a", "ă"),
                new Compose(DEAD_BREVE, "A", "Ă"),
                new Compose(DEAD_BREVE, "b", null),
                new Compose(DEAD_ABOVEDOT, " ", DEAD_ABOVEDOT),
                new Compose(DEAD_ABOVEDOT, "a", "ȧ"),
                new Compose(DEAD_ABOVEDOT, "A", "Ȧ"),
                new Compose(DEAD_ABOVEDOT, "u", null),
                new Compose(DEAD_CARON, " ", DEAD_CARON),
                new Compose(DEAD_CARON, "a", "ǎ"),
                new Compose(DEAD_CARON, "A", "Ǎ"),
                new Compose(DEAD_CARON, "b", null),
                new Compose(DEAD_HOOK, " ", DEAD_HOOK),
                new Compose(DEAD_HOOK, "a", "ả"),
                new Compose(DEAD_HOOK, "A", "Ả"),
                new Compose(DEAD_HOOK, ";", null),
                new Compose(DEAD_BELOWDOT, " ", DEAD_BELOWDOT),
                new Compose(DEAD_BELOWDOT, "a", "ạ"),
                new Compose(DEAD_BELOWDOT, "A", "Ạ"),
                new Compose(DEAD_BELOWDOT, "c", null),
                new Compose(DEAD_ACUTE, " ", DEAD_ACUTE),
                new Compose(DEAD_ACUTE, "a", "á"),
                new Compose(DEAD_ACUTE, "A", "Á"),
                new Compose(DEAD_ACUTE, "г", "ѓ"),
                new Compose(DEAD_ACUTE, "Г", "Ѓ"),
                new Compose(DEAD_ACUTE, "b", null),
                new Compose(DEAD_ACUTE, "ы", null),
                new Compose(DEAD_DIAERESIS, " ", DEAD_DIAERESIS),
                new Compose(DEAD_DIAERESIS, "a", "ä"),
                new Compose(DEAD_DIAERESIS, "A", "Ä"),
                new Compose(DEAD_DIAERESIS, "b", null),
                new Compose(DEAD_GRAVE, " ", DEAD_GRAVE),
                new Compose(DEAD_GRAVE, "a", "à"),
                new Compose(DEAD_GRAVE, "A", "À"),
                new Compose(DEAD_GRAVE, "и", "ѝ"),
                new Compose(DEAD_GRAVE, "И", "Ѝ"),
                new Compose(DEAD_GRAVE, "b", null),
                new Compose(DEAD_GRAVE, "ы", null),
                new Compose(DEAD_TILDE, " ", DEAD_TILDE),
                new Compose(DEAD_TILDE, "a", "ã"),
                new Compose(DEAD_TILDE, "A", "Ã"),
                new Compose(DEAD_TILDE, "b", null),
                // No DEAD_BELOWMACRON + Space
                new Compose(DEAD_BELOWMACRON, "b", "ḇ"),
                new Compose(DEAD_BELOWMACRON, "B", "Ḇ"),
                new Compose(DEAD_BELOWMACRON, "a", null),
        };
    }

    private static List<Integer> varyingKeys = List.of(
            VK_0, VK_1, VK_2, VK_3, VK_4, VK_5, VK_6, VK_7, VK_8, VK_9,
            VK_A, VK_B, VK_C, VK_D, VK_E, VK_F, VK_G, VK_H, VK_I, VK_J,
            VK_K, VK_L, VK_M, VK_N, VK_O, VK_P, VK_Q, VK_R, VK_S, VK_T,
            VK_U, VK_V, VK_W, VK_X, VK_Y, VK_Z, VK_COMMA, VK_PERIOD,
            VK_SLASH, VK_LESS, VK_MINUS, VK_EQUALS, VK_OPEN_BRACKET,
            VK_CLOSE_BRACKET, VK_SEMICOLON, VK_QUOTE, VK_BACK_QUOTE, VK_BACK_SLASH);

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
    private static final StringBuffer stringLog = new StringBuffer();
    private static final StringBuffer pendingLog = new StringBuffer();
    private static XKBLayoutData.LayoutDescriptor curLayout;
    private static XKBLayoutData.LayoutDescriptor curKeyCodeLayout;

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
                            pendingLog.append("\ttyped: U+" + String.format("%04X", (int) e.getKeyChar()) + "\n");
                        }

                        @Override
                        public void keyPressed(KeyEvent e) {
                            eventsTextArea.append("press: " + getKeyText(e.getKeyCode()) + "\n");
                            eventsTextArea.setCaretPosition(eventsTextArea.getDocument().getLength());
                            events.add(e);
                            pendingLog.append("\tpress: " + getKeyText(e.getKeyCode()) + "\n");
                        }

                        @Override
                        public void keyReleased(KeyEvent e) {
                            eventsTextArea.append("release: " + getKeyText(e.getKeyCode()) + "\n");
                            eventsTextArea.setCaretPosition(eventsTextArea.getDocument().getLength());
                            events.add(e);
                            pendingLog.append("\trelease: " + getKeyText(e.getKeyCode()) + "\n");
                        }
                    }
            );
        });

        robot = new Robot();
        robot.setAutoDelay(10);
        typedTextArea.requestFocusInWindow();
        robot.delay(500);

        boolean ok = true;

        for (var layout : XKBLayoutData.layouts) {
            if (!runTest(layout)) {
                ok = false;
                break;
            }
        }

        System.err.println("===== TEST RESULT =====");
        System.err.println(ok ? "TEST PASSED" : "TEST FAILED");
        System.err.println("===== FULL TEST LOG =====");
        System.err.println(stringLog.toString());

        frame.dispose();

        // Due to some reason that probably has something to do with the implementation
        // of the test driver, it's necessary to manually call System.exit() here
        System.exit(ok ? 0 : 1);
    }

    static void keyPress(int keyCode) {
        pendingLog.append("\tRobot keyPress: " + getKeyText(keyCode) + "\n");
        robot.keyPress(keyCode);
    }

    static void keyRelease(int keyCode) {
        pendingLog.append("\tRobot keyRelease: " + getKeyText(keyCode) + "\n");
        robot.keyRelease(keyCode);
    }

    static boolean runTest(XKBLayoutData.LayoutDescriptor layout) {
        curLayout = layout;
        if (layout.asciiCapable()) {
            curKeyCodeLayout = curLayout;
        } else {
            curKeyCodeLayout = XKBLayoutData.layouts.get(0); // US
        }

        infoTextArea.setText("");
        log("Layout: " + layout.layout() + ", variant: " + layout.variant() + "\n");
        frame.setTitle(layout.fullName());
        WLRobotPeer.setXKBLayout(layout.layout(), layout.variant(), "lv3:ralt_switch");
        robot.delay(500);

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

        robot.delay(500);

        boolean ok = true;

        for (int key : varyingKeys) {
            ok &= processKey(key);
        }

        robot.delay(100);

        return ok;
    }

    private static int getKeyCodeByName(String name) {
        try {
            return KeyEvent.class.getDeclaredField(name).getInt(KeyEvent.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean typeChar(String what) {
        for (var desc : curLayout.keys().values()) {
            for (int level = 0; level < desc.levels().size(); ++level) {
                var sym = desc.levels().get(level);
                if ((sym.xkbMods() & 0x81) != sym.xkbMods()) {
                    // we don't understand those mods
                    continue;
                }
                if (!sym.isDead() && sym.value().equals(what)) {
                    var keys = xkbModsToRobotKeycodes(sym.xkbMods());
                    keys.add(desc.robotCode());
                    for (var key : keys) {
                        keyPress(key);
                    }
                    for (var key : keys.reversed()) {
                        keyRelease(key);
                    }
                    robot.waitForIdle();
                    return true;
                }
            }
        }

        return false;
    }

    private static List<Integer> xkbModsToRobotKeycodes(int xkbMods) {
        var result = new ArrayList<Integer>();
        if ((xkbMods & 0x01) != 0) {
            result.add(VK_SHIFT);
        }
        if ((xkbMods & 0x80) != 0) {
            result.add(VK_ALT_GRAPH);
        }
        return result;
    }

    private static void checkKey(int keyCode, int level, XKBLayoutData.KeyDescriptor desc, Compose compose) {
        var sym = desc.levels().get(level);
        events.clear();
        typedTextArea.requestFocusInWindow();
        typed = "";
        robot.waitForIdle();

        var mods = xkbModsToRobotKeycodes(sym.xkbMods());
        for (var mod : mods) {
            keyPress(mod);
            log(getKeyText(mod) + " + ");
        }
        log(getKeyText(keyCode));
        if (compose != null) {
            log(", compose ");
            log(compose.key);
        }
        log(": ");
        keyPress(keyCode);
        keyRelease(keyCode);
        for (var mod : mods.reversed()) {
            keyRelease(mod);
        }
        robot.waitForIdle();

        List<KeyEvent> cleanEvents = new ArrayList<>();

        for (KeyEvent e : events) {
            if (mods.contains(e.getKeyCode())) continue;
            if (e.getKeyCode() == VK_ALT && mods.contains(VK_ALT_GRAPH)) continue;
            cleanEvents.add(e);
        }

        if (cleanEvents.size() != 2) {
            throw new RuntimeException("Expected two events, got: " + events.size());
        }

        if (cleanEvents.get(0).getID() != KeyEvent.KEY_PRESSED || cleanEvents.get(1).getID() != KeyEvent.KEY_RELEASED) {
            throw new RuntimeException("Expected one KEY_PRESSED and one KEY_RELEASED");
        }

        int expectedKeyCode = curKeyCodeLayout.keys().get(keyCode).javaKeyCode();

        if (cleanEvents.get(0).getKeyCode() != expectedKeyCode) {
            throw new RuntimeException("KEY_PRESSED keyCode = " + cleanEvents.get(0).getKeyCode() + ", expected " + expectedKeyCode);
        }

        if (cleanEvents.get(1).getKeyCode() != expectedKeyCode) {
            throw new RuntimeException("KEY_RELEASED keyCode = " + cleanEvents.get(1).getKeyCode() + ", expected " + expectedKeyCode);
        }

        String value = sym.value();

        if (sym.isDead()) {
            if (!typeChar(compose.key)) {
                keyPress(VK_ESCAPE);
                keyRelease(VK_ESCAPE);
                robot.waitForIdle();
                log("(skipped)\n");
                return;
            }
            value = compose.result == null ? "" : compose.result;
        }

        if (!typed.equals(value)) {
            throw new RuntimeException("KEY_TYPED: expected '" + value + "', got '" + typed + "'");
        }

        log("OK\n");
    }

    private static void log(String what) {
        infoTextArea.append(what);
        infoTextArea.setCaretPosition(infoTextArea.getDocument().getLength());
        stringLog.append(what);
    }

    private static boolean processKey(int keyCode) {
        var desc = curLayout.keys().get(keyCode);
        int maxLevel = desc.levels().size();
        if (maxLevel > 2 && curLayout.layout().equals("us") && curLayout.variant().equals("")) {
            // Fix for the 102nd key, which for some reason has 4 levels even though AltGr is not supported?
            maxLevel = 2;
        }

        boolean ok = true;
        for (int level = 0; level < maxLevel; ++level) {
            typedTextArea.append(getKeyText(keyCode) + ", level " + level + ": ");
            typedTextArea.setCaretPosition(typedTextArea.getDocument().getLength());
            var sym = desc.levels().get(level);
            if ((sym.xkbMods() & 0x81) != sym.xkbMods()) {
                // we don't understand those mods, so the test won't work
                continue;
            }

            List<Compose> composes = new ArrayList<>();
            if (sym.isDead()) {
                for (Compose c : Keys.composeSamples) {
                    if (c.dead.equals(sym.value())) {
                        composes.add(c);
                    }
                }
            } else {
                composes.add(null);
            }

            for (Compose compose : composes) {
                try {
                    checkKey(keyCode, level, desc, compose);
                } catch (RuntimeException e) {
                    log(e.getMessage());
                    log("\n");
                    ok = false;
                }
                if (!pendingLog.isEmpty()) {
                    stringLog.append(pendingLog);
                    pendingLog.setLength(0);
                }
            }

            typedTextArea.append("\n");
            typedTextArea.setCaretPosition(typedTextArea.getDocument().getLength());
        }
        return ok;
    }
}