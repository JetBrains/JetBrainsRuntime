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
