/*
 * Copyright 2024 JetBrains s.r.o.
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
import java.awt.Dimension;
import java.awt.Robot;

/**
 * @test
 * @summary Verifies that a window shown maximized really has the size
 *          it is supposed to have and ignores prior setSize()
 * @key headful
 * @run main ShowMaximized
 */

public class ShowMaximized {
    static JFrame frame;

    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();

        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("Maximized At Start");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                frame.setVisible(true);
            });
            pause(robot);

            if (frame.getExtendedState() != JFrame.MAXIMIZED_BOTH) {
                System.out.println("Can't maximize a window, skipping the test");
                return;
            }

            Dimension sizeWhenMaximized = frame.getSize();
            System.out.println("Size when maximized: " + sizeWhenMaximized);

            SwingUtilities.invokeAndWait(() -> {
                frame.setExtendedState(JFrame.NORMAL);
                frame.setVisible(false);
            });
            pause(robot);

            SwingUtilities.invokeAndWait(() -> {
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                frame.setSize(50, 50);
                frame.setVisible(true);
            });
            pause(robot);

            if (frame.getExtendedState() != JFrame.MAXIMIZED_BOTH) {
                System.out.println("Can't maximize the second time, skipping the test");
                return;
            }

            Dimension newSize = frame.getSize();
            System.out.println("Size when maximized again: " + sizeWhenMaximized);
            if (!sizeWhenMaximized.equals(newSize)) {
                throw new RuntimeException("Wrong size when maximized: got " + newSize + ", expected " + sizeWhenMaximized);
            } else {
                System.out.println("Test passed");
            }
        } finally {
            frame.dispose();
        }
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(1000);
    }
}
