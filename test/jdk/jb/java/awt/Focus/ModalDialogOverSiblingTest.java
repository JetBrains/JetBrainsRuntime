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
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;

/**
 * @test
 * @summary Regression test for JBR-1752 Floating windows overlap modal dialogs
 * @key headful
 */

public class ModalDialogOverSiblingTest {
    private static final CompletableFuture<Boolean> modalDialogButtonClicked = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(ModalDialogOverSiblingTest::initUI);
            robot.delay(2000);
            clickOn(button1);
            robot.delay(2000);
            clickOn(button2);
            robot.delay(2000);
            clickOn(button3);
            modalDialogButtonClicked.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(ModalDialogOverSiblingTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ModalDialogOverSiblingTest");
        frame.setLayout(new FlowLayout());
        button1 = new JButton("Open non-modal dialog");
        button1.addActionListener(e -> {
            JDialog dialog = new JDialog(frame, "Non-modal", false);
            dialog.setSize(200, 200);
            dialog.setLocation(200, 400);
            dialog.setVisible(true);
        });
        frame.add(button1);
        button2 = new JButton("Open modal dialog");
        button2.addActionListener(e -> {
            JDialog dialog = new JDialog(frame, "Modal", true);
            button3 = new JButton("Button");
            button3.addActionListener(event -> modalDialogButtonClicked.complete(true));
            dialog.add(button3);
            dialog.setSize(150, 150);
            dialog.setLocation(220, 420);
            dialog.setVisible(true);
        });
        frame.add(button2);
        frame.pack();
        frame.setLocation(200, 200);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
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
