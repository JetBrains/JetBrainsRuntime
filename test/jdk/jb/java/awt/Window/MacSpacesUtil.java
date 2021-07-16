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
import java.awt.event.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MacSpacesUtil {
    public static boolean hasMoreThanOneSpace() throws Exception {
        return Runtime.getRuntime().exec(new String[]{
                "plutil",
                "-extract",
                "SpacesDisplayConfiguration.Management Data.Monitors.0.Spaces.1",
                "json",
                "-o",
                "-",
                System.getProperty("user.home") + "/Library/Preferences/com.apple.spaces.plist"
        }).waitFor() == 0;
    }

    public static void addSpace() throws Exception {
        toggleMissionControl();

        // press button at top right corner to add a new space
        Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds();
        int rightX = screenBounds.x + screenBounds.width;
        int topY = screenBounds.y;
        Robot robot = new Robot();
        robot.setAutoDelay(50);
        robot.mouseMove(rightX, topY);
        robot.mouseMove(rightX - 5, topY + 5);
        robot.mouseMove(rightX - 10, topY + 10);
        robot.delay(1000);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(1000);

        toggleMissionControl();
    }

    public static boolean isWindowVisibleAtPoint(Window window, int x, int y) throws Exception {
        CountDownLatch movementDetected = new CountDownLatch(1);
        MouseMotionListener listener = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                movementDetected.countDown();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> window.addMouseMotionListener(listener));
            Robot robot = new Robot();
            robot.mouseMove(x, y);
            robot.delay(50);
            robot.mouseMove(x + 1, y + 1);
            return movementDetected.await(1, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(() -> window.removeMouseMotionListener(listener));
        }
    }

    public static boolean isWindowVisible(Window window) throws Exception {
        Rectangle bounds = window.getBounds();
        Insets insets = window.getInsets();
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return isWindowVisibleAtPoint(window, bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    public static void verifyAdditionalSpaceExists() throws Exception {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("Spaces check");
                frameRef.set(f);
                f.setBounds(100, 100, 200, 200);
                f.setVisible(true);
                Desktop.getDesktop().requestForeground(true);
            });

            if (!isWindowVisibleAtPoint(frameRef.get(), 200, 200)) {
                throw new RuntimeException("Test frame not visible");
            }

            switchToNextSpace();

            if (isWindowVisibleAtPoint(frameRef.get(), 200, 200)) {
                throw new RuntimeException("Extra space isn't available");
            }
        } finally {
            switchToPreviousSpace();
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = frameRef.get();
                if (f != null) f.dispose();
            });
        }
    }

    public static void toggleMissionControl() throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(50);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_UP);
        robot.keyRelease(KeyEvent.VK_UP);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(1000); // wait for animation to finish
    }

    public static void switchToPreviousSpace() throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(50);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_LEFT);
        robot.keyRelease(KeyEvent.VK_LEFT);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(1000); // wait for animation to finish
    }

    public static void switchToNextSpace() throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(50);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_RIGHT);
        robot.keyRelease(KeyEvent.VK_RIGHT);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(1000); // wait for animation to finish
    }

    // press Control+Right while holding mouse pressed over window's header
    public static void moveWindowToNextSpace(Window window) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(50);
        Point location = window.getLocationOnScreen();
        robot.mouseMove(location.x + window.getWidth() / 2, location.y + window.getInsets().top / 2);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        switchToNextSpace();
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public static void ensureMoreThanOneSpaceExists() throws Exception {
        if (hasMoreThanOneSpace()) return;
        addSpace();
        verifyAdditionalSpaceExists();
    }
}