/*
 * Copyright 2021 JetBrains s.r.o.
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
import java.util.concurrent.locks.LockSupport;

/**
 * @test
 * @summary Regression test for JBR-4131 Popup doesn't get focus if created from context menu
 * @key headful
 */

public class PopupFromMenuTest {
    private static final CompletableFuture<Boolean> typedInPopup = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField frameField;
    private static JMenuItem menuItem;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(PopupFromMenuTest::initUI);
            robot.delay(1000); // wait for frame to appear
            rightClickOn(frameField);
            robot.delay(1000); // wait for context menu to appear
            clickOn(menuItem);
            robot.delay(1000); // wait for popup to appear
            type();
            typedInPopup.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(PopupFromMenuTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("PopupFromMenuTest");
        frameField = new JTextField(20);
        JPopupMenu menu = new JPopupMenu();
        menuItem = new JMenuItem(new AbstractAction("open popup") {
            @Override
            public void actionPerformed(ActionEvent e) {
                JWindow popup = new JWindow(frame);
                JTextField popupField = new JTextField(10);
                popupField.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        typedInPopup.complete(true);
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {}

                    @Override
                    public void changedUpdate(DocumentEvent e) {}
                });
                popup.add(popupField);
                popup.pack();
                popup.setVisible(true);
                popupField.requestFocus();
                LockSupport.parkNanos(100_000_000);
            }
        });
        menu.add(menuItem);
        frameField.setComponentPopupMenu(menu);
        frame.add(frameField);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void type() {
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
    }

    private static void clickAt(int x, int y, int buttons) {
        robot.mouseMove(x, y);
        robot.delay(50); // without this test fails sometimes on macOS (due to popup menu closing at once)
        robot.mousePress(buttons);
        robot.mouseRelease(buttons);
    }

    private static void clickOn(Component component, int buttons) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2, buttons);
    }

    private static void clickOn(Component component) {
        clickOn(component, InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void rightClickOn(Component component) {
        clickOn(component, InputEvent.BUTTON3_DOWN_MASK);
    }
}
