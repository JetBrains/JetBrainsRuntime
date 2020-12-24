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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2977 Opening a recent project in a new window doesn't bring this window to the front
 * @key headful
 */

public class NewFrameAfterDialogTest {
    private static final CompletableFuture<Boolean> success = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JDialog dialog;
    private static JFrame frame2;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(NewFrameAfterDialogTest::initUI);
            robot.delay(3000); // wait for the frame to appear
            clickOn(frame);
            robot.delay(3000); // wait for dialog to appear
            clickOn(dialog);
            robot.delay(3000); // wait for second frame to appear
            clickOn(frame2);
            success.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(NewFrameAfterDialogTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("NewFrameAfterDialogTest");
        JButton button = new JButton("Open another frame");
        button.addActionListener(e -> {
            dialog = new JDialog(frame, true);
            JButton b = new JButton("Confirm");
            b.addActionListener(e2 -> dialog.dispose());
            dialog.add(b);
            dialog.setSize(300, 300);
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            frame2 = new JFrame("Second frame");
            JButton b2 = new JButton("Close");
            b2.addActionListener(e3 -> success.complete(true));
            frame2.add(b2);
            frame2.setSize(300, 300);
            frame2.setLocationRelativeTo(null);
            frame2.setVisible(true);
        });
        frame.add(button);
        frame.setSize(300, 300);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
        if (frame2 != null) frame2.dispose();
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
