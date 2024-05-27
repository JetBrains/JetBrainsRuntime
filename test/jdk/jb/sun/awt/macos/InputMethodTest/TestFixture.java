/*
 * Copyright 2024 JetBrains s.r.o.
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

import static java.awt.event.KeyEvent.KEY_PRESSED;
import static java.awt.event.KeyEvent.VK_ESCAPE;

public abstract class TestFixture {
    protected JFrame frame;
    protected Robot robot;
    protected JTextArea inputArea;

    private JLabel testNameLabel;
    private String testName;
    private String sectionName;
    private JTextArea testLogArea;
    private JScrollPane testLogAreaScroll;
    private List<KeyEvent> keyEvents = new ArrayList<>();
    private boolean success = true;

    record DomainKey(String domain, String key) {}

    private HashMap<DomainKey, String> oldDefaultValues = new HashMap<>();
    private String initialLayout;
    private Set<String> addedKeyboardLayouts = new HashSet<>();

    private void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Test");
            frame.setLayout(new BorderLayout());
            var size = new Dimension(800, 600);
            frame.setMinimumSize(size);
            frame.setSize(size);
            frame.setLocationRelativeTo(null);

            var panel = new JPanel();
            panel.setLayout(new GridBagLayout());
            frame.add(panel, BorderLayout.CENTER);

            testNameLabel = new JLabel();
            inputArea = new JTextArea();
            testLogArea = new JTextArea();
            testLogArea.setEditable(false);

            testLogAreaScroll = new JScrollPane(testLogArea);

            inputArea.addKeyListener(new KeyListener() {
                @Override
                public void keyTyped(KeyEvent e) {
                    handleKeyEvent(e);
                }

                @Override
                public void keyPressed(KeyEvent e) {
                    handleKeyEvent(e);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    handleKeyEvent(e);
                }
            });

            var constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.gridwidth = 1;
            constraints.weightx = 1.0;
            constraints.weighty = 0.0;
            constraints.anchor = GridBagConstraints.NORTH;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.ipadx = 10;
            constraints.ipady = 10;

            panel.add(testNameLabel, constraints);

            constraints.gridy++;
            panel.add(inputArea, constraints);

            for (var component : getExtraComponents()) {
                constraints.gridy++;
                panel.add(component, constraints);
            }

            constraints.gridy++;
            constraints.weighty = 1.0;
            constraints.anchor = GridBagConstraints.SOUTH;
            constraints.fill = GridBagConstraints.BOTH;
            panel.add(testLogAreaScroll, constraints);

            frame.setVisible(true);
            frame.setFocusable(true);
            frame.requestFocus();
        });

        robot = new Robot();
        robot.setAutoDelay(50);
        robot.delay(100);

        try {
            initialLayout = LWCToolkit.getKeyboardLayoutId();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            Toolkit.getDefaultToolkit().setLockingKeyState(KeyEvent.VK_CAPS_LOCK, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tearDown() {
        try {
            Toolkit.getDefaultToolkit().setLockingKeyState(KeyEvent.VK_CAPS_LOCK, false);
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (var entry : oldDefaultValues.entrySet()) {
            try {
                if (entry.getValue() == null) {
                    deleteDefaultImpl(entry.getKey().domain, entry.getKey().key);
                } else {
                    writeDefaultImpl(entry.getKey().domain, entry.getKey().key, entry.getValue());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        for (String layout : addedKeyboardLayouts) {
            try {
                LWCToolkit.disableKeyboardLayout(layout);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (initialLayout != null) {
            try {
                LWCToolkit.switchKeyboardLayout(initialLayout);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            robot.delay(100);
            SwingUtilities.invokeAndWait(() -> {
                frame.dispose();
            });
            robot.delay(100);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleKeyEvent(KeyEvent e) {
        keyEvents.add(e);
    }

    final protected void layout(String name) {
        List<String> layouts = new ArrayList<>();
        if (name.matches("com\\.apple\\.inputmethod\\.(SCIM|TCIM|TYIM|Korean|VietnameseIM|Kotoeri\\.\\w+)\\.\\w+")) {
            layouts.add(name.replaceFirst("\\.\\w+$", ""));
        }

        layouts.add(name);

        for (String layout : layouts) {
            if (!LWCToolkit.isKeyboardLayoutEnabled(layout)) {
                LWCToolkit.enableKeyboardLayout(layout);
                addedKeyboardLayouts.add(layout);
            }
        }

        LWCToolkit.switchKeyboardLayout(name);
        robot.delay(100);
    }

    private void updateTestNameLabel() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                testNameLabel.setText(testName + ": " + sectionName);
            });
        } catch (Exception e) {
            e.printStackTrace();
            success = false;
        }
    }

    private void prepareForNextSection() {
        inputArea.requestFocusInWindow();

        inputArea.enableInputMethods(false);
        robot.delay(50);

        robot.keyPress(VK_ESCAPE);
        robot.keyRelease(VK_ESCAPE);

        inputArea.enableInputMethods(true);
        robot.delay(50);

        inputArea.setText("");
        keyEvents.clear();
    }

    private void logMessage(String message) {
        System.err.println(message);
        try {
            SwingUtilities.invokeAndWait(() -> {
                testLogArea.append(message + "\n");
                testLogArea.setCaretPosition(testLogArea.getDocument().getLength());
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String keyWithModifiersToString(int keyCode, int modifiers) {
        StringBuilder message = new StringBuilder();

        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            message.append("Opt+");
        }

        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            message.append("Ctrl+");
        }

        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            message.append("Shift+");
        }

        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            message.append("Cmd+");
        }

        message.append(KeyEvent.getKeyText(keyCode));

        return message.toString();
    }

    private String readDefaultImpl(String domain, String key) {
        try {
            var exec = Runtime.getRuntime().exec(new String[]{"defaults", "read", domain, key});
            var exitCode = exec.waitFor();
            if (exitCode != 0) {
                return null;
            }
            var contents = exec.inputReader(StandardCharsets.UTF_8).readLine().strip();
            return contents;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("internal error");
        }
    }

    private void writeDefaultImpl(String domain, String key, String value) {
        try {
            Runtime.getRuntime().exec(new String[]{"defaults", "write", domain, key, value}).waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("internal error");
        }
    }

    private void deleteDefaultImpl(String domain, String key) {
        try {
            Runtime.getRuntime().exec(new String[]{"defaults", "delete", domain, key}).waitFor();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("internal error");
        }
    }

    private void setDefault(String domain, String key, String value) {
        var domainKey = new DomainKey(domain, key);
        if (!oldDefaultValues.containsKey(domainKey)) {
            oldDefaultValues.put(domainKey, readDefaultImpl(domain, key));
        }
        writeDefaultImpl(domain, key, value);
        robot.delay(50);
    }

    private void killProcess(String name) {
        try {
            Runtime.getRuntime().exec(new String[]{"killall", "-9", "-m", name}).waitFor();
        } catch (Exception exc) {
            exc.printStackTrace();
            throw new RuntimeException("internal error");
        }
    }

    private void restartJapaneseIM() {
        killProcess("JapaneseIM");
        robot.delay(1000);
    }

    final protected void chineseUseHalfWidthPunctuation(boolean value) {
        setDefault("com.apple.inputmethod.CoreChineseEngineFramework", "usesHalfwidthPunctuation", value ? "1" : "0");
    }

    final protected void japaneseUseBackslash(boolean value) {
        setDefault("com.apple.inputmethod.Kotoeri", "JIMPrefCharacterForYenKey", value ? "1" : "0");
        restartJapaneseIM();
    }

    final protected void romajiLayout(String layout) {
        setDefault("com.apple.inputmethod.Kotoeri", "JIMPrefRomajiKeyboardLayoutKey", layout);
        restartJapaneseIM();
    }

    final protected void beginTest(String name) {
        testName = name;
        sectionName = "";
        updateTestNameLabel();
        prepareForNextSection();
        logMessage("Begin test " + name);
    }

    final protected void section(String name) {
        sectionName = name;
        updateTestNameLabel();
        prepareForNextSection();
        logMessage("Begin section " + name);
    }

    final protected void press(int keyCode, int modifiers) {
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

        logMessage("Key press: " + keyWithModifiersToString(keyCode, modifiers));

        for (var modKey : modKeys) {
            robot.keyPress(modKey);
        }

        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);

        for (var modKey : modKeys) {
            robot.keyRelease(modKey);
        }
    }

    final protected void press(int keyCode) {
        press(keyCode, 0);
    }

    final protected void delay(int ms) {
        robot.delay(ms);
    }

    final protected String getText() {
        String[] text = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                text[0] = inputArea.getText();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return text[0];
    }

    final protected void expect(boolean value, String comment) {
        if (value) {
            logMessage(comment + ": OK");
        } else {
            success = false;
            logMessage(comment + ": FAIL");
        }
    }

    final protected void expectEquals(Object left, Object right) {
        if (Objects.equals(left, right)) {
            logMessage(left + " == " + right + ": OK");
        } else {
            success = false;
            logMessage(left + " != " + right + ": FAIL");
        }
    }

    final protected void expectNotEquals(Object left, Object right) {
        if (Objects.equals(left, right)) {
            success = false;
            logMessage(left + " == " + right + ": FAIL");
        } else {
            logMessage(left + " != " + right + ": OK");
        }
    }

    final protected void expectText(String text) {
        try {
            expectEquals(text, getText());
        } catch (Exception e) {
            e.printStackTrace();
            success = false;
        }
    }

    final protected void expectKeyPressed(int key, int modifiers) {
        var pressed = keyEvents.stream().filter(e -> e.getID() == KEY_PRESSED).toList();

        var foundKey = false;

        for (var e : pressed) {
            if (e.getKeyCode() == key) {
                expectEquals(e.getModifiersEx(), modifiers);
                foundKey = true;
            }
        }

        expect(foundKey, "expected key press: " + keyWithModifiersToString(key, modifiers));
    }

    final protected void setCapsLockState(boolean desiredState) {
        Toolkit.getDefaultToolkit().setLockingKeyState(KeyEvent.VK_CAPS_LOCK, desiredState);
        robot.delay(250);
    }

    final public void run() {
        try {
            setUp();
            beginTest(this.getClass().getName());
            test();
        } catch (Exception e) {
            e.printStackTrace();
            success = false;
        }

        try {
            tearDown();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (success) {
            System.out.println("TEST PASSED");
        } else {
            throw new RuntimeException("TEST FAILED");
        }
    }

    protected List<JComponent> getExtraComponents() {
        return new ArrayList<>();
    }

    abstract protected void test() throws Exception;
}
