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

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;

/*
 * Description: The class generates mouse movements.
 *
 */
public class LWCToolkit {

    private static Robot robot;
    private static JFrame staticFrame;
    private static Rectangle frameBounds;

    private static int ITERATION_NUMBER = 10;

    private static void doTest() {

        int x1 = frameBounds.x + frameBounds.width - 1;
        int y1 = frameBounds.y + frameBounds.height - 1;
        int x2 = frameBounds.x + frameBounds.width * 5 - 1;
        int y2 = frameBounds.y + frameBounds.height * 5 - 1;

        if (MouseInfo.getNumberOfButtons() < 1)
            throw new RuntimeException("The systems without a mouse");

        int button = 1;
        int buttonMask = InputEvent.getMaskForButton(button);

        for (int i = 0; i < ITERATION_NUMBER; i++) {
            robot.mouseMove(x1, y1);
            robot.waitForIdle();

            robot.mousePress(buttonMask);
            robot.waitForIdle();


            for (int j=2; j<10;j++) {

                robot.mouseMove(frameBounds.x + frameBounds.width * j - 1, frameBounds.y + frameBounds.height * j - 1);
                robot.waitForIdle();

            }
            robot.mouseRelease(buttonMask);
            robot.waitForIdle();

            robot.mousePress(buttonMask);
            robot.waitForIdle();

            for (int j=8; j>1;j--) {

                robot.mouseMove(frameBounds.x + frameBounds.width * j - 1, frameBounds.y + frameBounds.height * j - 1);
                robot.waitForIdle();

            }

            robot.mouseMove(x1, y1);
            robot.waitForIdle();

            robot.mouseRelease(buttonMask);
            robot.waitForIdle();
        }
    }

    private static void createAndShowGUI() {
        staticFrame = new JFrame("BuffFlusher");
        staticFrame.setSize(400, 400);
        staticFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout());
        panel.addMouseMotionListener(new MouseMotionListener() {

            @Override
            public void mouseDragged(MouseEvent e) {
            }

            @Override
            public void mouseMoved(MouseEvent e) {
            }
        });
        staticFrame.add(panel, BorderLayout.CENTER);
        staticFrame.pack();
        staticFrame.setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0)
            ITERATION_NUMBER = Integer.parseInt(args[0]);

        try {
            robot = new Robot();
            robot.setAutoDelay(50);

            SwingUtilities.invokeAndWait(LWCToolkit::createAndShowGUI);
            robot.waitForIdle();

            SwingUtilities.invokeAndWait(() -> frameBounds = staticFrame.getBounds());
            robot.waitForIdle();

            doTest();

        } finally {
            SwingUtilities.invokeLater(() -> {
                if (staticFrame != null) {
                    staticFrame.setVisible(false);
                    staticFrame.dispose();
                }
            });
        }
    }
}
