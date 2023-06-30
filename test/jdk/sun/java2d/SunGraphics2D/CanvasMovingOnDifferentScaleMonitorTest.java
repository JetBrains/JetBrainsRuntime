/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

/**
 * @test
 * @summary checking correctness of redrawing JFrame with Canvas after dragging between monitors with different scales
 * @requires (os.family == "windows")
 * @run main CanvasMovingOnDifferentScaleMonitorTest
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;

class JFrameWithCanvas extends JFrame {
    public JFrameWithCanvas() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setTitle("JFrameWithCanvas");
        setBackground(Color.GREEN);
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);
        setContentPane((new JPanel() {
            private final Canvas canvas = new Canvas() {
                public void paint(Graphics g) {
                    g.setColor(Color.WHITE);
                    g.drawLine(0, 0, getWidth(), getHeight());
                    g.drawLine(getWidth(), 0, 0, getHeight());
                }

                {
                    setBackground(new Color(15527148));
                }
            };

            public void doLayout() {
                if (!canvas.getSize().equals(getSize())) {
                    canvas.setSize(getWidth(), getHeight());
                }
                canvas.validate();
            }

            {
                setBackground(Color.RED);
                setPreferredSize(new Dimension(600, 400));
                add(canvas);
            }
        }));
        pack();
        setLocation(300, 300);
        setVisible(true);
    }
}

public class CanvasMovingOnDifferentScaleMonitorTest {
    private static boolean colorContains(Color color, BufferedImage bufferedImage) {
        for (int y = 0; y < bufferedImage.getHeight(); y++) {
            for (int x = 0; x < bufferedImage.getWidth(); x++) {
                if (bufferedImage.getRGB(x, y) == color.getRGB()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void main(String[] args) {
        GraphicsDevice[] graphicsDevices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (graphicsDevices.length < 2) {
            throw new RuntimeException("Failed: incorrect setup");
        }

        JFrameWithCanvas jFrame = new JFrameWithCanvas();
        int baseX = jFrame.getX() + 50;
        int baseY = jFrame.getY() + 20;
        int movedX = baseX + graphicsDevices[0].getDisplayMode().getWidth();
        String status = "PASSED";
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(100);
            robot.setAutoWaitForIdle(true);
            robot.mouseMove(baseX, baseY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 0; i < 10; i++) {
                robot.mouseMove(movedX, baseY);
                robot.mouseMove(baseX, baseY);
                if (colorContains(Color.RED, robot.createScreenCapture(
                        new Rectangle(baseX, baseY, jFrame.getWidth(), jFrame.getHeight())))) {
                    status = "Failed: red color appears";
                    break;
                }
            }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (AWTException e) {
            status = "Failed: internal error";
        }
        jFrame.dispose();

        if (!status.equals("PASSED")) {
            throw new RuntimeException(status);
        }
        System.out.println(status);
    }
}