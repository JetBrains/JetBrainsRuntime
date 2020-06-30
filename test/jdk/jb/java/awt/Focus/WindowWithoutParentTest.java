/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2854 [macOS] Undecorated window without parent steals focus on showing
 * @key headful
 */

public class WindowWithoutParentTest {
    private static final CompletableFuture<Boolean> initFinished = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> typedInField = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField field;
    private static JWindow window;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50); // ensure different timestamps for key events (can impact typeahead logic)
        try {
            SwingUtilities.invokeAndWait(WindowWithoutParentTest::initUI);
            initFinished.get(10, TimeUnit.SECONDS);
            clickOn(field);
            pressAndRelease(KeyEvent.VK_ENTER);
            pressAndRelease(KeyEvent.VK_A);
            typedInField.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(WindowWithoutParentTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("WindowWithoutParentTest");
        field = new JTextField(20);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                initFinished.complete(true);
            }
        });
        field.addActionListener(e -> {
            window = new JWindow();
            window.setSize(50, 50);
            window.setVisible(true);
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typedInField.complete(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {}

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        frame.add(field);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (window != null) window.dispose();
        if (frame != null) frame.dispose();
    }

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y) {
        robot.delay(1000); // needed for GNOME, to give it some time to update internal state after window showing
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
