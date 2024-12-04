/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * @requires os.family == "linux"
 * @summary Verifies that robot correctly pick color
 * @run main/othervm  -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True RobotGetOOBPixelsTest
 * @run main/othervm  -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=False RobotGetOOBPixelsTest
 */


public class RobotGetOOBPixelsTest {
    final static int W = 600;
    final static int H = 600;

    final static CountDownLatch latchShownFrame = new CountDownLatch(1);
    static volatile boolean failed = false;

    public static void main(String[] args) throws InterruptedException, AWTException {
        final Robot robot = new Robot();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Robot Test");
            frame.setSize(W, H);
            frame.add(new JPanel(){
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.setColor(Color.RED);
                    g.fillRect(0, 0, W/3, H/3);
                    g.setColor(Color.GREEN);
                    g.fillRect(W/3, H/3, W/3, H/3);
                    g.setColor(Color.BLUE);
                    g.fillRect((2*W)/3, (2*H)/3, W/3, H/3);
                }
            });

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    int [] extremeValues = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1,
                            Integer.MAX_VALUE};
                    for (int val1 : extremeValues) {
                        for (int val2 : extremeValues) {
                            try {
                                robot.createScreenCapture(
                                        new Rectangle(val1, val2, frame.getWidth(), frame.getHeight()));
                                failed = true;
                            } catch (IndexOutOfBoundsException ex) {
                                // Expected exception
                            }
                        }
                    }

                    try {
                        robot.createScreenCapture(
                                new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
                        failed = true;
                    } catch (IndexOutOfBoundsException ex) {
                        // Expected exception
                    }

                    latchShownFrame.countDown();
                }
            });
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });

        latchShownFrame.await();
        if (failed) throw new RuntimeException("Test failed");
    }
}