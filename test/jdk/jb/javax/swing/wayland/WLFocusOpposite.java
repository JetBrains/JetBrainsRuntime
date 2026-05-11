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

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;

/**
 * @test
 * @summary Verifies that focus events include the correct 'opposite' window
 * @requires os.family == "linux"
 * @key headful
 * @library /test/lib
 * @run main WLFocusOpposite
 */
public class WLFocusOpposite {
    static JFrame frame;
    static JWindow popup;
    static JButton openButton;
    static JButton closeButton;
    static Window oppositeWindow;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("WLFocusOpposite Test");
                openButton = new JButton("Open popup");
                openButton.addActionListener(e -> {
                    popup = new JWindow(frame);
                    closeButton = new JButton("Close popup");
                    closeButton.addActionListener(ee -> popup.dispose());
                    popup.add(closeButton);
                    popup.setSize(200, 100);
                    popup.setVisible(true);
                });
                frame.add(openButton);
                frame.setSize(400, 300);
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setVisible(true);
            });

            Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
                if (e instanceof WindowEvent we) {
                    oppositeWindow = we.getOppositeWindow();
                    System.out.printf("Window event: %s%n---opposite window was: %s%n", e, oppositeWindow);
                }
            }, AWTEvent.WINDOW_FOCUS_EVENT_MASK);

            Robot robot = new Robot();
            robot.waitForIdle();
            robot.delay(500);

            openButton.doClick();
            robot.waitForIdle();
            robot.delay(500);

            closeButton.doClick();
            robot.waitForIdle();
            robot.delay(500);

            if (oppositeWindow != popup) {
                throw new RuntimeException("Unexpected opposite window: " + oppositeWindow);
            }
        } finally {
            if (frame != null) frame.dispose();
        }
    }
}
