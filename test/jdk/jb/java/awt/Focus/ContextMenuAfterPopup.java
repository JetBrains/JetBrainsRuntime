/*
 * Copyright 2022 JetBrains s.r.o.
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
import java.awt.*;
import java.awt.event.*;

/**
 * @test
 * @summary Regression test for JBR-4820 Focus lost after showing popup and context menu
 * @key headful
 */

public class ContextMenuAfterPopup {
    private static Robot robot;
    private static JFrame frame;
    private static JTextField frameField;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(ContextMenuAfterPopup::initUI);
            robot.delay(1000);
            clickOn(frameField);
            robot.delay(1000);
            type(KeyEvent.VK_ENTER);
            robot.delay(1000);
            type(KeyEvent.VK_SPACE);
            robot.delay(1000);
            type(KeyEvent.VK_ESCAPE);
            robot.delay(1000);
            type(KeyEvent.VK_A);
            robot.delay(1000);
            if (!"a".equals(frameField.getText())) {
                throw new RuntimeException();
            }
        } finally {
            SwingUtilities.invokeAndWait(ContextMenuAfterPopup::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ContextMenuAfterPopup");
        frameField = new JTextField(30);
        frameField.addActionListener(e -> {
            JWindow popup = new JWindow(frame);
            JButton b = new JButton("Show context menu");
            b.addActionListener(ee -> {
                popup.dispose();
                JPopupMenu pm = new JPopupMenu();
                pm.add("item 1");
                pm.add("item 2");
                pm.show(frameField, 20, 20);
            });
            popup.add(b);
            popup.pack();
            popup.setVisible(true);
        });
        frame.add(frameField);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void type(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
