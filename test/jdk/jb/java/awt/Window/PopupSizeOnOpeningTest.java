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

/**
 * @test
 * @summary Regression test for JBR-3024 Popups are shown with 1x1 size sometimes
 * @key headful
 */

public class PopupSizeOnOpeningTest {
    private static Robot robot;
    private static JFrame frame;
    private static JLabel clickPlace;
    private static JWindow popup;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(PopupSizeOnOpeningTest::initUI);
            robot.delay(3000);
            clickAt(clickPlace); // make sure frame is focused

            for (int i = 0; i < 10; i++) {
                robot.delay(1000);
                pressAndReleaseSpace(); // open popup
                robot.delay(1000);
                Dimension size = popup.getSize();
                if (size.width <= 1 || size.height <= 1) {
                    throw new RuntimeException("Unexpected popup size: " + size);
                }
                pressAndReleaseSpace(); // close popup
            }
        } finally {
            SwingUtilities.invokeAndWait(PopupSizeOnOpeningTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("PopupSizeOnOpeningTest");
        frame.setLayout(new FlowLayout());
        frame.add(clickPlace = new JLabel("Click here"));
        JButton openButton = new JButton("Open popup");
        openButton.addActionListener(e -> {
            popup = new JWindow(frame);
            JButton closeButton = new JButton("Close popup");
            closeButton.addActionListener(ev -> {
                popup.dispose();
                popup = null;
            });
            popup.add(closeButton);
            popup.pack();
            popup.setLocationRelativeTo(frame);
            popup.setVisible(true);
        });
        frame.add(openButton);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void pressAndReleaseSpace() {
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
