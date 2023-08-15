/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import javax.swing.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;


/**
 * @test
 * @key headful
 * @summary  Windows HiDPI support
 * @requires (os.family == "linux")
 * @library /test/lib
 * @build ScreenCapture
 * @run driver WakefieldTestDriver -resolution 1x1400x800 ScreenCapture
 * @run driver WakefieldTestDriver -resolution 2x830x800 ScreenCapture
 */

public class ScreenCapture {
    private static final Color[] COLORS = {
            Color.GREEN, Color.BLUE, Color.ORANGE, Color.RED};

    private static volatile ScreenCapture theTest;

    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        runSwing( () -> {
                    frame = new JFrame();
                    frame.setPreferredSize(new Dimension(800, 300));
                    frame.setUndecorated(true);
                    frame.add(new JPanel() {
                        @Override
                        public void paint(Graphics g) {
                            super.paintComponent(g);
                            final int w = getWidth();
                            final int h = getHeight();
                            g.setColor(COLORS[0]);
                            g.fillRect(0, 0, w / 2, h / 2);
                            g.setColor(COLORS[1]);
                            g.fillRect(w / 2, 0, w / 2, h / 2);
                            g.setColor(COLORS[2]);
                            g.fillRect(0, h / 2, w / 2, h / 2);
                            g.setColor(COLORS[3]);
                            g.fillRect(w / 2, h / 2, w / 2, h / 2);
                        }
                    });
                    frame.pack();
                    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    frame.setVisible(true);
                    frame.setBounds(432, 89, 800, 300);
                });

        final Robot robot = new Robot();
        robot.waitForIdle();

        final Rectangle rect = frame.getBounds();
        rect.setLocation(frame.getLocationOnScreen());

        // the windows fades in slowly, need to wait quite a bit in order to get reliable colors
        robot.delay(1000);

        System.out.println("Creating screen capture of " + rect);
        final BufferedImage image = robot.createScreenCapture(rect);

        try {
            checkPixelColor(robot, rect.x + OFFSET, rect.y + OFFSET, COLORS[0]);
            checkPixelColor(robot, rect.x + rect.width - OFFSET, rect.y + OFFSET, COLORS[1]);
            checkPixelColor(robot, rect.x + OFFSET, rect.y + rect.height - OFFSET, COLORS[2]);
            checkPixelColor(robot, rect.x + rect.width - OFFSET, rect.y + rect.height - OFFSET, COLORS[3]);
        } finally {
            frame.dispose();
        }

        final int w = image.getWidth();
        final int h = image.getHeight();

        if (w != frame.getWidth() || h != frame.getHeight()) {
            throw new RuntimeException("Wrong image size!");
        }

        checkRectColor(image, new Rectangle(0, 0, w / 2, h / 2), COLORS[0]);
        checkRectColor(image, new Rectangle(w / 2, 0, w / 2, h / 2), COLORS[1]);
        checkRectColor(image, new Rectangle(0, h / 2, w / 2, h / 2), COLORS[2]);
        checkRectColor(image, new Rectangle(w / 2, h / 2, w / 2, h / 2), COLORS[3]);

        System.exit(0); // temporary
    }

    private static final int OFFSET = 5;

    static void checkPixelColor(Robot robot, int x, int y, Color expectedColor) {
        System.out.print("Checking pixel at " + x + ", " + y + " to have color " + expectedColor);
        final Color actualColor = robot.getPixelColor(x, y);
        if (!actualColor.equals(expectedColor)) {
            System.out.println("... Mismatch: found " + actualColor + " instead");
            throw new RuntimeException("Wrong color of pixel on screen");
        } else {
            System.out.println("... OK");
        }
    }

    static void checkRectColor(BufferedImage image, Rectangle rect, Color expectedColor) {
        System.out.println("Checking rectangle " + rect + " to have color " + expectedColor);
        final Point[] pointsToCheck = new Point[] {
                new Point(rect.x + OFFSET, rect.y + OFFSET),                           // top left corner
                new Point(rect.x + rect.width - OFFSET, rect.y + OFFSET),              // top right corner
                new Point(rect.x + rect.width / 2, rect.y + rect.height / 2),          // center
                new Point(rect.x + OFFSET, rect.y + rect.height - OFFSET),             // bottom left corner
                new Point(rect.x + rect.width - OFFSET, rect.y + rect.height - OFFSET) // bottom right corner
        };

        for (final var point : pointsToCheck) {
            System.out.print("Checking color at " + point + " to be equal to " + expectedColor);
            final int actualColor = image.getRGB(point.x, point.y);
            if (actualColor != expectedColor.getRGB()) {
                System.out.println("... Mismatch: found " + new Color(actualColor) + " instead. Check image.png.");
                try {
                    ImageIO.write(image, "png", new File("image.png"));
                } catch(IOException e) {
                    System.out.println("failed to save image.png.");
                    e.printStackTrace();
                }
                throw new RuntimeException("Wrong image color!");
            } else {
                System.out.println("... OK");
            }
        }
    }

    public void dispose() {
        if (frame != null) {
            frame.dispose();
            frame = null;
        }
    }

    private static void runSwing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
