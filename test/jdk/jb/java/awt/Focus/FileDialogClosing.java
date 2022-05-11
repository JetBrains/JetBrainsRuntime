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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @test
 * @summary Regression test for JBR-4546 Focus is not returned back to IDE after closing "Open" dialog
 * @key headful
 */

public class FileDialogClosing {
    private static final AtomicInteger pressedCount = new AtomicInteger();

    private static Robot robot;
    private static Frame frame;
    private static Button button;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(FileDialogClosing::initUI);
            robot.delay(1000);
            clickOn(button);
            robot.delay(1000);
            pressAndRelease(KeyEvent.VK_ESCAPE);
            robot.delay(1000);
            pressAndRelease(KeyEvent.VK_SPACE);
            robot.delay(1000);
            if (pressedCount.get() != 2) {
                throw new RuntimeException("Unexpected pressed count: " + pressedCount);
            }
        } finally {
            SwingUtilities.invokeAndWait(FileDialogClosing::disposeUI);
        }
    }

    private static void initUI() {
        frame = new Frame("FileDialogClosing");
        button = new Button("Open dialog");
        button.addActionListener(e -> {
            pressedCount.incrementAndGet();
            new FileDialog(frame).setVisible(true);
        });
        frame.add(button);
        frame.setBounds(200, 200, 200, 200);
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

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }
}
