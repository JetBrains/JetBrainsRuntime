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
import java.util.concurrent.locks.LockSupport;

/**
 * @test
 * @summary Regression test for JBR-2712 Typeahead mechanism doesn't work on Windows
 * @key headful
 */

public class TypeaheadToFrontTest {
    private static final CompletableFuture<Boolean> initFinished = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> typedInPopup = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField frameField;
    private static JWindow window;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50); // ensure different timestamps for key events (can impact typeahead logic)
        try {
            SwingUtilities.invokeAndWait(TypeaheadToFrontTest::initUI);
            initFinished.get(10, TimeUnit.SECONDS);
            clickOn(frameField);
            SwingUtilities.invokeAndWait(TypeaheadToFrontTest::showPopup);
            pressAndRelease(KeyEvent.VK_ENTER);
            pressAndRelease(KeyEvent.VK_A);
            typedInPopup.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(TypeaheadToFrontTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("TypeaheadToFrontTest");
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                initFinished.complete(true);
            }
        });
        frameField = new JTextField(20);
        frameField.addActionListener(e -> {
            LockSupport.parkNanos(1_000_000_000L);
            window.toFront();
        });
        frame.add(frameField);
        frame.pack();
        frame.setVisible(true);
    }

    private static void showPopup() {
        window = new JWindow(frame);
        JTextField windowField = new JTextField(20);
        windowField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typedInPopup.complete(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {}

            @Override
            public void changedUpdate(DocumentEvent e) {}
        });
        window.add(windowField);
        window.pack();
        window.setAutoRequestFocus(false);
        window.setVisible(true);
        window.setAutoRequestFocus(true);
    }

    private static void disposeUI() {
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
