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
import java.awt.Insets;
import java.awt.Robot;

/**
 * @test
 * @summary Verifies that window insets are correct in and out of the fullscreen mode
 *          when the window is maximized
 * @key headful
 */

public class FullscreenInsets {
    private static final int INITIAL_WIDTH = 400;
    private static final int INITIAL_HEIGHT = 300;

    private static JFrame frame;
    private static Robot robot;
    private static Insets initialInsets;
    private static Insets insetsInFullscreen;
    private static Insets insetsOutFullscreen;
    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(FullscreenInsets::initUI);
            delay();

            SwingUtilities.invokeAndWait(() -> initialInsets = frame.getInsets());
            System.out.printf("Initial insets: %s%n", initialInsets);

            SwingUtilities.invokeAndWait(() -> frame.getGraphicsConfiguration().getDevice().setFullScreenWindow(frame));
            delay();

            SwingUtilities.invokeAndWait(() -> insetsInFullscreen = frame.getInsets());
            System.out.printf("Insets in fullscreen: %s%n", insetsInFullscreen);

            SwingUtilities.invokeAndWait(() -> frame.getGraphicsConfiguration().getDevice().setFullScreenWindow(null));
            delay();

            SwingUtilities.invokeAndWait(() -> insetsOutFullscreen = frame.getInsets());
            System.out.printf("Insets out of fullscreen: %s%n", insetsOutFullscreen);

            checkInsets();

        } finally {
            SwingUtilities.invokeAndWait(FullscreenInsets::disposeUI);
        }
    }

    private static void checkInsets() {
        if (!initialInsets.equals(insetsOutFullscreen)) {
            throw new RuntimeException("Initial insets are not equal to insets after leaving fullscreen");
        }
        if (!insetsInFullscreen.equals(new Insets(0, 0, 0, 0))) {
            throw new RuntimeException("Insets in fullscreen are non-zero");
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
