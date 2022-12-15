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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-5109 New frame doesn't get focused sometimes if it's shown right after popup is
 *          closed
 * @key headful
 */

public class FrameAfterPopup {
    private static final CompletableFuture<Boolean> success = new CompletableFuture<>();

    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static JButton button;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(FrameAfterPopup::initUI);
            robot.delay(1000);
            clickOn(button);
            robot.delay(1000);
            pressAndRelease(KeyEvent.VK_SPACE);
            success.get(5, TimeUnit.SECONDS);
        }
        finally {
            SwingUtilities.invokeAndWait(FrameAfterPopup::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("FrameAfterPopup");
        frame1.setBounds(200, 200, 200, 200);
        frame1.setVisible(true);

        JWindow w = new JWindow(frame1);
        button = new JButton("New frame");
        button.addActionListener(e -> {
            w.dispose();
            frame2 = new JFrame("Another frame");
            JButton b = new JButton("Finish");
            b.addActionListener(ee -> success.complete(true));
            frame2.add(b);
            frame2.setBounds(300, 300, 200, 200);
            frame2.setVisible(true);
        });
        w.add(button);
        w.setBounds(250, 250, 200, 200);
        w.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
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