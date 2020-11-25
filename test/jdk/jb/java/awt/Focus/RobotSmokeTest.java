/*
 * Copyright 2020-2023 JetBrains s.r.o.
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
 * @summary Test validating basic java.awt.Robot functionality (mouse clicks and key presses)
 * @key headful
 */

public class RobotSmokeTest {
    private static final CompletableFuture<Boolean> mouseClicked = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> keyTyped = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField field;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(RobotSmokeTest::initUI);
            robot.delay(5000);
            clickOn(field);
            mouseClicked.get(10, TimeUnit.SECONDS);
            pressAndRelease(KeyEvent.VK_A);
            keyTyped.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(RobotSmokeTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("RobotSmokeTest");
        field = new JTextField(20);
        field.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                mouseClicked.complete(Boolean.TRUE);
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                keyTyped.complete(Boolean.TRUE);
            }
        });
        frame.add(field);
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
