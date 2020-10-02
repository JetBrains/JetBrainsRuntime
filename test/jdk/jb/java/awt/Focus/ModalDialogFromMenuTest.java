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
import java.util.concurrent.locks.LockSupport;

/**
 * @test
 * @summary Test for the case which was broken during development for JBR-2712 (Typeahead mechanism doesn't work on Windows)
 * @key headful
 */

public class ModalDialogFromMenuTest {
    private static final CompletableFuture<Boolean> initFinished = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> menuItemShown = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> typedInDialog = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField frameField;
    private static JMenuItem menuItem;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50); // ensure different timestamps for key events (can impact typeahead logic)
        try {
            SwingUtilities.invokeAndWait(ModalDialogFromMenuTest::initUI);
            initFinished.get(10, TimeUnit.SECONDS);
            rightClickOn(frameField);
            menuItemShown.get(10, TimeUnit.SECONDS);
            clickOn(menuItem);
            pressAndRelease(KeyEvent.VK_A);
            typedInDialog.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(ModalDialogFromMenuTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ModalDialogFromMenuTest");
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                initFinished.complete(true);
            }
        });
        frameField = new JTextField(20);
        JPopupMenu menu = new JPopupMenu();
        menuItem = new JMenuItem(new AbstractAction("open dialog") {
            @Override
            public void actionPerformed(ActionEvent e) {
                JDialog d = new JDialog(frame, true);
                JTextField dialogField = new JTextField(10);
                dialogField.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        typedInDialog.complete(true);
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {}

                    @Override
                    public void changedUpdate(DocumentEvent e) {}
                });
                d.add(dialogField);
                d.pack();
                d.setVisible(true);
            }
        });
        menuItem.addHierarchyListener(e -> {
            if (menuItem.isShowing()) menuItemShown.complete(true);
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

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y, int buttons) {
        robot.mouseMove(x, y);
        robot.mousePress(buttons);
        robot.mouseRelease(buttons);
    }

    private static void clickOn(Component component, int buttons) {
        robot.delay(1000); // needed for GNOME, to give it some time to update internal state after window showing
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
