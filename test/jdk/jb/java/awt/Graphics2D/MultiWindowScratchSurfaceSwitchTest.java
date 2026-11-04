/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * @test
 * @bug 8378506
 * @key headful
 * @summary Verifies that window content rendered right before the Metal
 *          render queue switches to the scratch surface (here: creating an
 *          accelerated VolatileImage) is presented on screen, even though the
 *          same render queue flush ends on another window. This is the
 *          SET_SCRATCH_SURFACE counterpart of sun/java2d/OpenGL/MultiWindowFillTest,
 *          which only covers the SET_SURFACES switch between two windows.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true MultiWindowScratchSurfaceSwitchTest
 */
public final class MultiWindowScratchSurfaceSwitchTest {

    private static final int SIZE = 100;
    private static final int TOLERANCE = 10;
    private static final int ITERATIONS = 5;
    private static final Color[] COLORS = {
            Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA, Color.CYAN
    };

    public static void main(String[] args) throws Exception {
        Frame f1 = new Frame("f1");
        Frame f2 = new Frame("f2");
        try {
            f1.setUndecorated(true);
            f1.setSize(SIZE, SIZE);
            f1.setLocation(100, 100);
            f2.setUndecorated(true);
            f2.setSize(SIZE, SIZE);
            f2.setLocation(300, 100);

            f1.setVisible(true);
            f2.setVisible(true);

            Robot robot = new Robot();
            robot.waitForIdle();
            robot.delay(1000);

            int w = f1.getWidth();
            int h = f1.getHeight();
            GraphicsConfiguration gc = f1.getGraphicsConfiguration();

            for (int i = 0; i < ITERATIONS; i++) {
                Color c1 = COLORS[i % COLORS.length];
                Color c2 = COLORS[(i + 2) % COLORS.length];

                // 1. Render to the first window: the ops stay queued in the
                //    render queue until the next flush.
                fill(f1, c1, w, h);

                // 2. Creating an accelerated image makes the render queue
                //    switch to the scratch surface (SET_SCRATCH_SURFACE) and
                //    flush synchronously. The content of f1 must be presented
                //    at this switch: nothing else renders to f1 afterwards.
                VolatileImage vi = gc.createCompatibleVolatileImage(SIZE, SIZE);
                vi.validate(gc);

                // 3. Render to the second window: the flush ends on f2.
                fill(f2, c2, w, h);

                robot.waitForIdle();
                robot.delay(500);

                check(robot, f1, w, h, c1, "iteration " + i + ": f1");
                check(robot, f2, w, h, c2, "iteration " + i + ": f2");

                vi.flush();
            }
        } finally {
            f1.dispose();
            f2.dispose();
        }
    }

    private static void fill(Frame frame, Color c, int w, int h) {
        Graphics g = frame.getGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
    }

    private static void check(Robot robot, Frame frame, int w, int h,
                              Color exp, String desc)
    {
        Point loc = frame.getLocationOnScreen();
        Color c = robot.getPixelColor(loc.x + w / 2, loc.y + h / 2);
        if (!isAlmostEqual(c, exp)) {
            saveImage(robot, frame, frame.getTitle());
            throw new RuntimeException("%s: %s != %s".formatted(desc, exp, c));
        }
    }

    private static void saveImage(Robot r, Frame f, String name) {
        try {
            Rectangle rect = f.getBounds();
            BufferedImage img = r.createScreenCapture(rect);
            ImageIO.write(img, "png", new File(name + ".png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isAlmostEqual(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed()) <= TOLERANCE
                && Math.abs(c1.getGreen() - c2.getGreen()) <= TOLERANCE
                && Math.abs(c1.getBlue() - c2.getBlue()) <= TOLERANCE;
    }
}
