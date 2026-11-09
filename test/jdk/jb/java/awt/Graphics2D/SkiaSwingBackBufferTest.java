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
 */

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Robot;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary On the Skia pipeline on Windows a window surface is an off-screen texture presented
 *          when its paint cycle ends, so Swing paints straight into it without a back buffer of
 *          its own (sun.java2d.skia.swingBackBuffer=true keeps it, and so does present=flush);
 *          either way a frame shows what its component paints, also after a repaint.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaSwingBackBufferTest false
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.swingBackBuffer=true SkiaSwingBackBufferTest true
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.present=flush SkiaSwingBackBufferTest true
 */
public class SkiaSwingBackBufferTest {
    static volatile Color fill = Color.RED;
    static JFrame frame;
    static JPanel panel;

    public static void main(String[] args) throws Exception {
        boolean backBufferExpected = Boolean.parseBoolean(args[0]);
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("SkiaSwingBackBufferTest");
                frame.setUndecorated(true);
                panel = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        g.setColor(fill);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
                panel.setPreferredSize(new Dimension(200, 200));
                frame.add(panel);
                frame.pack();
                frame.setLocation(100, 100);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
            });
            robot.waitForIdle();
            robot.delay(1000);
            if (!SKEnvironment.isSkiaEnabled()) {
                System.out.println("Skia is not available: nothing to test");
                return;
            }
            boolean[] backBuffer = new boolean[1];
            SwingUtilities.invokeAndWait(() -> backBuffer[0] = RepaintManager.currentManager(panel).isDoubleBufferingEnabled());
            System.out.println("Swing back buffer: " + backBuffer[0] + " (expected " + backBufferExpected + ")");
            if (backBuffer[0] != backBufferExpected) {
                throw new RuntimeException("Swing's back buffer is " + (backBuffer[0] ? "on" : "off")
                        + ", expected " + (backBufferExpected ? "on" : "off"));
            }
            check(robot, Color.RED);
            fill = Color.BLUE;
            SwingUtilities.invokeAndWait(() -> panel.repaint());
            robot.waitForIdle();
            robot.delay(500);
            check(robot, Color.BLUE);
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

    static void check(Robot robot, Color expected) throws Exception {
        Point[] at = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            Point p = panel.getLocationOnScreen();
            at[0] = new Point(p.x + 100, p.y + 100);
        });
        Color c = robot.getPixelColor(at[0].x, at[0].y);
        if (Math.abs(c.getRed() - expected.getRed()) > 8 || Math.abs(c.getGreen() - expected.getGreen()) > 8
                || Math.abs(c.getBlue() - expected.getBlue()) > 8) {
            throw new RuntimeException("the window shows " + c + ", expected " + expected);
        }
    }
}
