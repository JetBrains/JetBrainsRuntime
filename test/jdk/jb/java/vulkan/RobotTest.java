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
import java.util.concurrent.Future;

/*
 * @test
 * @summary Verifies that robot correctly pick color
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True RobotTest
 */


public class RobotTest {
    final static CountDownLatch latchShownFrame = new CountDownLatch(1);
    static volatile boolean failed = false;

    public static void main(String[] args) throws InterruptedException {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Robot Test");
            frame.setSize(600, 600);
            frame.add(new JPanel(){
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    g.setColor(Color.RED);
                    g.fillRect(0, 0, 600, 600);

                }
            });

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    try {
                        Robot r = new Robot();
                        Color pixel = r.getPixelColor((int) (frame.getX() + frame.getInsets().bottom + frame.getWidth() / 2.0),
                                (int) (frame.getY() + frame.getInsets().left + frame.getHeight() / 2.0));
                        failed = pixel.getRed() < 250 || pixel.getGreen() > 10 || pixel.getBlue() > 10;
                        latchShownFrame.countDown();
                    } catch (AWTException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            });
            frame.setVisible(true);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }



            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        });

        latchShownFrame.await();
        if (failed) throw new RuntimeException("Test failed");
    }
}