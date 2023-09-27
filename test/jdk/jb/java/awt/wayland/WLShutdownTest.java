/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * @summary Verifies that the program finishes after disposing of JFrame
 * @run main WLShutdownTest
 */
public class WLShutdownTest {
    private static JFrame frame = null;

    public static void main(String[] args) throws Exception {
        final CountDownLatch latchShownFrame = new CountDownLatch(1);
        final CountDownLatch latchClosedFrame = new CountDownLatch(1);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("TEST");

                frame.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentShown(ComponentEvent e) {
                        latchShownFrame.countDown();
                    }
                });
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        latchClosedFrame.countDown();
                    }
                });

                JPanel panel = new JPanel() {
                    int n = 0;

                    @Override
                    protected void paintComponent(Graphics g) {
                        System.out.print("P");
                        g.setColor((n++ % 2 == 0) ? Color.RED : Color.BLUE);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        System.out.print("Q");
                    }
                };

                panel.setPreferredSize(new Dimension(800, 800));
                panel.setBackground(Color.BLACK);
                frame.add(panel);
                frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                frame.pack();
                frame.setVisible(true);
                System.out.print(">>");
            }
        });

        // Wait frame to be shown:
        latchShownFrame.await();

        System.out.print(":>>");

        final long startTime = System.currentTimeMillis();
        final long endTime = startTime + 3000;

        // Start 1st measurement:
        repaint();

        for (; ; ) {
            System.out.print(".");

            repaint();

            if (System.currentTimeMillis() >= endTime) {
                break;
            }
            sleep();
        } // end measurements

        SwingUtilities.invokeAndWait(() -> {
            frame.setVisible(false);
            frame.dispose();
        });

        latchClosedFrame.await();
        System.out.print("<<\n");

        frame = null; // free static ref: gc
        System.out.println("Waiting AWT to shutdown JVM soon ...");
    }


    static void repaint() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame.repaint();
            }
        });
    }

    static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            ie.printStackTrace(System.err);
        }
    }
}
