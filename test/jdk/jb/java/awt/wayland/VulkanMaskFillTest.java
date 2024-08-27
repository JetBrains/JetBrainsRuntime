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

import sun.java2d.vulkan.VKInstance;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * @requires os.family == "linux"
 * @summary Verifies that Vulkan mask fill works
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true VulkanMaskFillTest
 */


public class VulkanMaskFillTest {
    final static int W = 600;
    final static int H = 600;

    final static CountDownLatch latchShownFrame = new CountDownLatch(1);
    static volatile boolean failed = false;

    static boolean compareColors(Color c1, Color c2, double tolerance) {
        return Math.abs(c1.getRed() - c2.getRed()) <= tolerance &&
                Math.abs(c1.getGreen() - c2.getGreen()) <= tolerance &&
                Math.abs(c1.getBlue() - c2.getBlue()) <= tolerance;
    }

    public static void main(String[] args) throws InterruptedException, AWTException {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance()) {
            System.out.println("No WLToolkit, skipping test");
            return;
        }
        if (!VKInstance.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }
        if (!VKInstance.isSurfaceDataAccelerated()) {
            throw new Error("Accelerated surface data not enabled");
        }

        final Robot robot = new Robot();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Vulkan Mask Fill Test");
            frame.setSize(W, H);
            frame.add(new JPanel(){
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                    g.setColor(Color.MAGENTA);
                    g.fillRect(0, 0, W/3, H/3);
                    g.fillRect(W/3, H/3, W/3, H/3);
                    g.fillRect((2*W)/3, (2*H)/3, W/3, H/3);
                    // Antialiased ovals are drawn via MASK_FILL.
                    ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Color.RED);
                    g.fillOval(0, 0, W/3, H/3);
                    g.setColor(Color.GREEN);
                    g.fillOval(W/3, H/3, W/3, H/3);
                    g.setColor(Color.BLUE);
                    g.fillOval((2*W)/3, (2*H)/3, W/3, H/3);
                }
            });

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    var loc = frame.getLocationOnScreen();
                    int x = loc.x + frame.getInsets().bottom + W/6;
                    int y = loc.y + frame.getInsets().left + H/6;
                    try {
                        Color c = robot.getPixelColor(x, y);
                        if (!compareColors(c, Color.RED, 0)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }

                        x += W / 3;
                        y += H / 3;

                        c = robot.getPixelColor(x, y);
                        if (!compareColors(c, Color.GREEN, 0)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }

                        x += W / 3;
                        y += H / 3;

                        c = robot.getPixelColor(x, y);
                        if (!compareColors(c, Color.BLUE, 0)) {
                            System.out.println("Unexpected color: " + c + " at (" + x + ", " + y + ")");
                            failed = true;
                        }
                    } catch (Throwable t) {
                        failed = true;
                        System.out.println("Unexpected exception:");
                        t.printStackTrace(System.out);
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
