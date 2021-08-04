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
 * @summary Regression test for JBR-3662 Focus jumps to another project tab after closing modal dialog
 * @key headful
 */

public class WrongFrameFocusedOnModalDialogClosing {
    private static CompletableFuture<Boolean> result;

    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static JDialog dialog;
    private static JButton button;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(WrongFrameFocusedOnModalDialogClosing::initUI);
            robot.delay(1000); // wait for frames to appear
            clickOn(button);
            robot.delay(1000); // wait for dialog to appear
            SwingUtilities.invokeAndWait(() -> {
                result = new CompletableFuture<>();
                dialog.dispose();
            });
            if (result.get(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Wrong frame focused");
            }
        } finally {
            SwingUtilities.invokeAndWait(WrongFrameFocusedOnModalDialogClosing::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("WFFOMDC 1");
        frame1.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (result != null) result.complete(false);
            }
        });
        dialog = new JDialog(frame1, true);
        button = new JButton("Open dialog");
        button.addActionListener(e -> dialog.setVisible(true));
        frame1.add(button);
        frame1.setSize(100, 100);
        frame1.setLocation(100, 100);
        frame1.setVisible(true);

        frame2 = new JFrame("WFFOMDC 2");
        frame2.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if (result != null) result.complete(true);
            }
        });
        frame2.setSize(100, 100);
        frame2.setLocation(300, 100);
        frame2.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
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
