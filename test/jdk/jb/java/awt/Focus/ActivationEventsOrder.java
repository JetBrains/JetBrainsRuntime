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
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3989 Broken focus state after a quick succession of activation/deactivation events
 *          on Windows
 * @key headful
 */

public class ActivationEventsOrder {
    private static final CompletableFuture<Boolean> success = new CompletableFuture<>();

    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static JTextField field1;
    private static JTextField field2;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(ActivationEventsOrder::initUI);
            robot.delay(1000); // wait for windows to appear
            SwingUtilities.invokeLater(ActivationEventsOrder::delayedFocusRequest);
            robot.delay(1000); // wait for EDT to be frozen in Thread.sleep
            clickOn(field1);
            robot.delay(2000); // wait for freeze to be over
            clickOn(field2);
            robot.delay(1000); // wait for focus to settle down
            pressAndRelease(KeyEvent.VK_ENTER);
            success.get(5, TimeUnit.SECONDS);
        }
        finally {
            SwingUtilities.invokeAndWait(ActivationEventsOrder::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("ActivationEventsOrder 1");
        field1 = new JTextField();
        frame1.add(field1);
        frame1.setBounds(100, 100, 200, 100);
        frame1.setVisible(true);

        frame2 = new JFrame("ActivationEventsOrder 2");
        field2 = new JTextField();
        frame2.add(field2);
        frame2.setBounds(100, 300, 200, 100);
        frame2.setVisible(true);

        field2.addActionListener(e -> success.complete(true));
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
    }

    private static void delayedFocusRequest() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        frame2.toFront();
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