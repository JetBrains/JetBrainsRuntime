/*
 * Copyright 2026 JetBrains s.r.o.
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
import java.awt.Robot;

/**
 * @test
 * @summary Verifies that window's location matches one from getLocationOnScreen()
 * @requires os.family == "linux"
 * @key headful
 * @run main/othervm WLWindowLocation
 */
public class WLWindowLocation {
    private static JFrame frame;
    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("WLWindowLocation Test");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setSize(300, 200);
                frame.setLocation(142, 342); // will be corrected by window's native peer
                frame.setVisible(true);
            });

            Robot robot = new Robot();
            robot.waitForIdle();
            robot.delay(500);

            SwingUtilities.invokeAndWait(() -> {
                var locOnScreen = frame.getLocationOnScreen();
                var locFromBounds = frame.getBounds().getLocation();
                System.out.printf("Location on screen: %s\n", locOnScreen);
                System.out.printf("Location from bounds: %s\n", locFromBounds);
                if (!locOnScreen.equals(locFromBounds)) {
                    throw new RuntimeException("Frame locations do not match: %s (screen) vs %s (bounds)".formatted(locOnScreen, locFromBounds));
                }
            });

            SwingUtilities.invokeAndWait(() -> {
                frame.setLocation(-42, 42);
            });

            robot.waitForIdle();
            robot.delay(500);

            SwingUtilities.invokeAndWait(() -> {
                var locOnScreen = frame.getLocationOnScreen();
                var locFromBounds = frame.getBounds().getLocation();
                System.out.printf("Location on screen: %s\n", locOnScreen);
                System.out.printf("Location from bounds: %s\n", locFromBounds);
                if (!locOnScreen.equals(locFromBounds)) {
                    throw new RuntimeException("Frame locations do not match: %s (screen) vs %s (bounds)".formatted(locOnScreen, locFromBounds));
                }
            });
        } finally {
            if (frame != null) {
                frame.dispose();
            }
        }
    }
}
