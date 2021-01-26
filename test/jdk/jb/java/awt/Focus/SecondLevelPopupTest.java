/*
 * Copyright 2021-2023 JetBrains s.r.o.
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
 * @summary Regression test for JBR-3054 Focus is not returned to frame after closing of second-level popup on Windows
 * @key headful
 */

public class SecondLevelPopupTest {
    private static CompletableFuture<Boolean> button1Focused;
    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(SecondLevelPopupTest::initUI);
            robot.delay(2000);
            clickOn(button1);
            robot.delay(2000);
            clickOn(button2);
            robot.delay(2000);
            SwingUtilities.invokeAndWait(() -> button1Focused = new CompletableFuture<>());
            clickOn(button3);
            button1Focused.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(SecondLevelPopupTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("SecondLevelPopupTest");
        button1 = new JButton("Open popup");
        button1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (button1Focused != null) button1Focused.complete(true);
            }
        });
        button1.addActionListener(e -> {
            JWindow w = new JWindow(frame);
            w.setFocusableWindowState(false);
            button2 = new JButton("Open another popup");
            button2.addActionListener(ee -> {
                JWindow ww = new JWindow(w);
                button3 = new JButton("Close");
                button3.addActionListener(eee -> {
                    ww.dispose();
                });
                ww.add(button3);
                ww.pack();
                ww.setLocation(200, 400);
                ww.setVisible(true);
            });
            w.add(button2);
            w.pack();
            w.setLocation(200, 300);
            w.setVisible(true);
        });
        frame.add(button1);
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
