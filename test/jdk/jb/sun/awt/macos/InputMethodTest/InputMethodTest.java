/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sun.lwawt.macosx.LWCToolkit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InputMethodTest {
    private static JFrame frame;
    private static JTextArea textArea;
    private static Robot robot;
    private static String currentTest = "";
    private static String currentSection = "";
    private static String initialLayout;
    private static final Set<String> addedLayouts = new HashSet<>();
    private static boolean success = true;
    private static int lastKeyCode = -1;

    private enum TestCases {
        DeadKeysTest (new DeadKeysTest()),
        KeyCodesTest (new KeyCodesTest()),
        PinyinCapsLockTest (new PinyinCapsLockTest()),
        PinyinFullWidthPunctuationTest (new PinyinFullWidthPunctuationTest()),
        PinyinHalfWidthPunctuationTest (new PinyinHalfWidthPunctuationTest()),
        PinyinQuotesTest (new PinyinQuotesTest())
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
        System.exit(success ? 0 : 1);
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
            public void keyTyped(KeyEvent keyEvent) {}

            @Override
            public void keyPressed(KeyEvent keyEvent) {
                lastKeyCode = keyEvent.getKeyCode();
            }

            @Override
            public void keyReleased(KeyEvent keyEvent) {}
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
            System.out.printf("Test %s (%s) failed: %s\n", currentTest, currentSection, e);
            success = false;
        }
    }

    public static void section(String description) {
        currentSection = description;
        textArea.setText("");
        frame.setTitle(currentTest + ": " + description);
    }

    public static void layout(String name) {
        List<String> layouts = new ArrayList<>();
        if (name.matches("com\\.apple\\.inputmethod\\.(SCIM|TCIM|TYIM)\\.\\w+")) {
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

    public static void type(int key, int modifiers) {
        lastKeyCode = -1;
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

    public static void expect(String expectedValue) {
        var actualValue = textArea.getText();
        if (actualValue.equals(expectedValue)) {
            System.out.printf("Test %s (%s) passed, got '%s'\n", currentTest, currentSection, actualValue);
        } else {
            success = false;
            System.out.printf("Test %s (%s) failed, expected '%s', got '%s'\n", currentTest, currentSection, expectedValue, actualValue);
        }
    }

    public static void expectKeyCode(int keyCode) {
        if (lastKeyCode == keyCode) {
            System.out.printf("Test %s (%s) passed, got key code %d\n", currentTest, currentSection, keyCode);
        } else {
            success = false;
            System.out.printf("Test %s (%s) failed, expected key code %d, got %d\n", currentTest, currentSection, keyCode, lastKeyCode);
        }
    }

    public static void expectTrue(boolean value, String comment) {
        if (value) {
            System.out.printf("Test %s (%s) passed: %s\n", currentTest, currentSection, comment);
        } else {
            success = false;
            System.out.printf("Test %s (%s) failed: %s\n", currentTest, currentSection, comment);
        }
    }
}
