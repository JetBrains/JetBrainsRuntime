/*
 * Copyright 2025 JetBrains s.r.o.
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

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Robot;

/**
 * @test
 * @summary Verifies that a maximized window location is correctly reported
 *          after exiting from fullscreen mode
 * @key headful
 */

public class LocationAfterFullscreen {
    private static final int INITIAL_WIDTH = 400;
    private static final int INITIAL_HEIGHT = 300;

    private static JFrame frame;
    private static Robot robot;
    private static Point initialLocation;
    private static Point locationInFullscreen;
    private static Point locationOutFullscreen;
    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(LocationAfterFullscreen::initUI);
            delay();

            var device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            var deviceLocation = device.getDefaultConfiguration().getBounds().getLocation();
            System.out.printf("Device location: %s%n", deviceLocation);

            SwingUtilities.invokeAndWait(() -> initialLocation = frame.getLocationOnScreen());
            System.out.printf("=================================Initial location: %s%n%n", initialLocation);

            SwingUtilities.invokeAndWait(() -> device.setFullScreenWindow(frame));
            delay();

            SwingUtilities.invokeAndWait(() -> locationInFullscreen = frame.getLocationOnScreen());
            System.out.printf("=================================Location in fullscreen: %s%n%n", locationInFullscreen);

            SwingUtilities.invokeAndWait(() -> device.setFullScreenWindow(null));
            delay();

            SwingUtilities.invokeAndWait(() -> locationOutFullscreen = frame.getLocationOnScreen());
            System.out.printf("=================================Location out of fullscreen: %s%n%n", locationOutFullscreen);

            if (!locationInFullscreen.equals(deviceLocation)) {
                throw new RuntimeException(String.format("Location in fullscreen (%s) differs from device (%s)", locationInFullscreen, deviceLocation));
            }

            if (!locationOutFullscreen.equals(initialLocation)) {
                throw new RuntimeException(String.format("Location out of fullscreen (%s) differs from initial (%s)", locationOutFullscreen, initialLocation));
            }

        } finally {
            SwingUtilities.invokeAndWait(LocationAfterFullscreen::disposeUI);
        }
    }

    private static void delay() throws Exception {
        robot.waitForIdle();
        robot.delay(1000);
    }

    private static void initUI() {
        frame = new JFrame("RestoreFromFullScreen");
        frame.setBounds(200, 200, INITIAL_WIDTH, INITIAL_HEIGHT);
        frame.setUndecorated(false);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }
}
