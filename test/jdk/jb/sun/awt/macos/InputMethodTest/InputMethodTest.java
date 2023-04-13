/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import sun.lwawt.macosx.LWCToolkit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class InputMethodTest {
    private static JFrame frame;
    private static JTextArea textArea;
    private static Robot robot;
    private static String currentTest = "";
    private static String currentSection = "";
    private static String initialLayout;
    private static final Set<String> addedLayouts = new HashSet<>();
    private static boolean success = true;
    private static final List<KeyEvent> triggeredEvents = new ArrayList<>();

    private enum TestCases {
        DeadKeysTest (new DeadKeysTest()),
        KeyCodesTest (new KeyCodesTest()),
        PinyinCapsLockTest (new PinyinCapsLockTest()),
        PinyinFullWidthPunctuationTest (new PinyinFullWidthPunctuationTest()),
        PinyinHalfWidthPunctuationTest (new PinyinHalfWidthPunctuationTest()),
        PinyinQuotesTest (new PinyinQuotesTest()),
        RomajiYenTest (new RomajiYenTest(false)),
        RomajiYenBackslashTest (new RomajiYenTest(true))
        ;

        private Runnable test;

        TestCases(Runnable runnable) {
            test = runnable;
        }

        public void run() {
            test.run();
        }
    }

    public static void main(String[] args) {
        init();
        try {
            for (String arg : args) {
                runTest(arg);
            }
        } finally {
            setCapsLockState(false);
            LWCToolkit.switchKeyboardLayout(initialLayout);
            for (String layoutId : addedLayouts) {
                try {
                    LWCToolkit.disableKeyboardLayout(layoutId);
                } catch (Exception ignored) {}
            }
        }
        if (success) {
            System.out.println("TEST PASSED");
        } else {
            throw new RuntimeException("TEST FAILED: check output");
        }
    }

    private static void init() {
        try {
            robot = new Robot();
            robot.setAutoDelay(100);
        } catch (AWTException e) {
            e.printStackTrace();
            System.exit(1);
        }

        initialLayout = LWCToolkit.getKeyboardLayoutId();

        frame = new JFrame("InputMethodTest");
        frame.setVisible(true);
        frame.setSize(300, 300);
        frame.setLocationRelativeTo(null);

        textArea = new JTextArea();
        textArea.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent keyEvent) {
                triggeredEvents.add(keyEvent);
            }

            @Override
            public void keyPressed(KeyEvent keyEvent) {
                triggeredEvents.add(keyEvent);
            }

            @Override
            public void keyReleased(KeyEvent keyEvent) {
                triggeredEvents.add(keyEvent);
            }
        });

        frame.setLayout(new BorderLayout());
        frame.getContentPane().add(textArea, BorderLayout.CENTER);

        textArea.grabFocus();
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
    }

    private static void runTest(String name) {
        currentTest = name;
        setCapsLockState(false);
        try {
            TestCases.valueOf(name).run();
        } catch (Exception e) {
            System.out.printf("Test %s (%s) FAILED: %s\n", currentTest, currentSection, e);
            success = false;
        }
    }

    private static String readDefault(String domain, String key) {
        try {
            var proc = Runtime.getRuntime().exec(new String[]{"defaults", "read", domain, key});
            var exitCode = proc.waitFor();
            if (exitCode == 0) {
                return new Scanner(proc.getInputStream()).next();
            }
        } catch (Exception exc) {
            exc.printStackTrace();
            throw new RuntimeException("internal error");
        }

        return null;
    }

    private static void writeDefault(String domain, String key, String value) {
        try {
            Runtime.getRuntime().exec(new String[]{"defaults", "write", domain, key, value}).waitFor();
        } catch (Exception exc) {
            exc.printStackTrace();
            throw new RuntimeException("internal error");
        }
    }

    public static void section(String description) {
        // clear dead key state
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);

        currentSection = description;
        textArea.setText("");
        frame.setTitle(currentTest + ": " + description);
        triggeredEvents.clear();
    }

    public static void layout(String name) {
        List<String> layouts = new ArrayList<>();
        if (name.matches("com\\.apple\\.inputmethod\\.(SCIM|TCIM|TYIM|Kotoeri\\.\\w+)\\.\\w+")) {
            layouts.add(name.replaceFirst("\\.\\w+$", ""));
        }

        layouts.add(name);

        for (String layout : layouts) {
            if (!LWCToolkit.isKeyboardLayoutEnabled(layout)) {
                LWCToolkit.enableKeyboardLayout(layout);
                addedLayouts.add(layout);
            }
        }

        LWCToolkit.switchKeyboardLayout(name);
        robot.delay(250);
    }

    public static void setUseHalfWidthPunctuation(boolean flag) {
        writeDefault("com.apple.inputmethod.CoreChineseEngineFramework", "usesHalfwidthPunctuation", flag ? "1" : "0");
    }

    public static void setUseBackslashInsteadOfYen(boolean flag) {
        writeDefault("com.apple.inputmethod.Kotoeri", "JIMPrefCharacterForYenKey", flag ? "1" : "0");

        // Need to kill Kotoeri, since it doesn't reload the config otherwise. This makes me sad.
        try {
            Runtime.getRuntime().exec(new String[]{"killall", "-9", "-m", "JapaneseIM"}).waitFor();
        } catch (Exception exc) {
            exc.printStackTrace();
            throw new RuntimeException("internal error");
        }

        // wait for it to restart...
        robot.delay(3000);
    }

    public static void type(int key, int modifiers) {
        List<Integer> modKeys = new ArrayList<>();

        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            modKeys.add(KeyEvent.VK_ALT);
        }

        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            modKeys.add(KeyEvent.VK_CONTROL);
        }

        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            modKeys.add(KeyEvent.VK_SHIFT);
        }

        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            modKeys.add(KeyEvent.VK_META);
        }

        for (var modKey : modKeys) {
            robot.keyPress(modKey);
        }

        robot.keyPress(key);
        robot.keyRelease(key);

        for (var modKey : modKeys) {
            robot.keyRelease(modKey);
        }
    }

    public static void setCapsLockState(boolean desiredState) {
        LWCToolkit.getDefaultToolkit().setLockingKeyState(KeyEvent.VK_CAPS_LOCK, desiredState);
        robot.delay(250);
    }

    public static List<KeyEvent> getTriggeredEvents() {
        return Collections.unmodifiableList(triggeredEvents);
    }

    public static void expect(String expectedValue) {
        var actualValue = textArea.getText();
        if (actualValue.equals(expectedValue)) {
            System.out.printf("Test %s (%s) passed, got '%s'\n", currentTest, currentSection, actualValue);
        } else {
            success = false;
            System.out.printf("Test %s (%s) FAILED, expected '%s', got '%s'\n", currentTest, currentSection, expectedValue, actualValue);
        }
    }

    public static void expectTrue(boolean value, String comment) {
        if (value) {
            System.out.printf("Test %s (%s) passed: %s\n", currentTest, currentSection, comment);
        } else {
            success = false;
            System.out.printf("Test %s (%s) FAILED: %s\n", currentTest, currentSection, comment);
        }
    }

    public static void fail(String comment) {
        expectTrue(false, comment);
    }
}
