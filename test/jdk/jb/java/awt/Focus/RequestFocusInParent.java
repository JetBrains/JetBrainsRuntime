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
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3979 Focus is not transferred to parent window
 * @key headful
 */

public class RequestFocusInParent {
    private static CompletableFuture<Boolean> result;

    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(RequestFocusInParent::initUI);
            robot.delay(1000); // wait for frame to appear
            clickOn(button1);
            robot.delay(1000); // wait for popup to appear
            SwingUtilities.invokeAndWait(() -> result = new CompletableFuture<>());
            clickOn(button2);
            result.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(RequestFocusInParent::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("RequestFocusInParent");
        button1 = new JButton("Open popup");
        button1.addActionListener(e -> {
            JWindow popup = new JWindow(frame);
            button2 = new JButton("Return focus");
            button2.addActionListener(ee -> button1.requestFocus());
            popup.add(button2);
            popup.setSize(100, 100);
            popup.setLocation(100, 400);
            popup.setVisible(true);
        });
        button1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (result != null) result.complete(true);
            }
        });
        frame.add(button1);
        frame.setSize(100, 100);
        frame.setLocation(100, 100);
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
