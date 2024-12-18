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
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * @requires os.family == "linux"
 * @summary Verifies that robot correctly pick color
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True RobotGetPixelsTest
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=False RobotGetPixelsTest
 */


public class RobotGetPixelsTest {
    final static int W = 600;
    final static int H = 600;

    final static CountDownLatch latchShownFrame = new CountDownLatch(1);
    static volatile boolean failed = false;

    static boolean compareColors(Color c1, Color c2, double tolerance) {
        return Math.abs(c1.getRed() - c2.getRed()) < tolerance &&
                Math.abs(c1.getGreen() - c2.getGreen()) < tolerance &&
                Math.abs(c1.getBlue() - c2.getBlue()) < tolerance;
    }

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
                    try {
                        BufferedImage bi = robot.createScreenCapture(
                                new Rectangle(frame.getX(), frame.getY(), frame.getWidth(), frame.getHeight()));
                        int x = frame.getInsets().bottom + W / 6;
                        int y = frame.getInsets().left + H / 6;

                        Color c = new Color(bi.getRGB(x, y));
                        if (!compareColors(c, Color.RED, 10)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }

                        x += W / 3;
                        y += H / 3;

                        c = new Color(bi.getRGB(x, y));
                        if (!compareColors(c, Color.GREEN, 10)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }

                        x += W / 3;
                        y += H / 3;

                        c = new Color(bi.getRGB(x, y));
                        if (!compareColors(c, Color.BLUE, 10)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }
                    } catch (Throwable t) {
                        System.out.println("Unexpected exception:");
                        t.printStackTrace(System.out);
                        failed = true;
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