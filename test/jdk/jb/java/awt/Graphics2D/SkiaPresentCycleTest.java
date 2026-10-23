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
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary With sun.java2d.skia.present=cycle a Skia window is presented when
 *          its paint cycle ends (Swing's updateWindows, Toolkit.sync, the
 *          flusher's timeout) rather than at the end of every queue flush, as
 *          the other pipelines do. What reaches the screen must not change:
 *          a Swing repaint shows up, drawing on the window outside any paint
 *          cycle shows up within the timeout, and after Toolkit.sync it is
 *          there at once. Both modes run the same checks. When Skia is
 *          unavailable there is nothing to test.
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.present=flush -Dsun.java2d.uiScale=1 SkiaPresentCycleTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.present=cycle -Dsun.java2d.uiScale=1 SkiaPresentCycleTest
 */
public class SkiaPresentCycleTest {
    static final int TIMEOUT_MS = 3000;

    static volatile Color panelColor = Color.RED;

    public static void main(String[] args) throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        boolean cycleRequested = "cycle".equals(System.getProperty("sun.java2d.skia.present"));
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        System.out.println("present=" + System.getProperty("sun.java2d.skia.present") + ", presentsPerCycle "
                + SKEnvironment.presentsPerCycle());
        if (SKEnvironment.presentsPerCycle() != (cycleRequested && windows)) {
            throw new RuntimeException("presentsPerCycle() does not follow the property");
        }
        Robot robot = new Robot();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<JPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("SkiaPresentCycleTest");
            f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            f.setUndecorated(true);
            JPanel p = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(panelColor);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            f.setContentPane(p);
            f.setSize(300, 200);
            f.setLocation(100, 100);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            frameRef.set(f);
            panelRef.set(p);
        });
        JFrame frame = frameRef.get();
        JPanel panel = panelRef.get();
        try {
            robot.waitForIdle();
            Point centre = centreOf(panel);
            expect(robot, centre, Color.RED, "the first paint");

            // 1. a Swing repaint: the paint cycle ends in updateWindows
            panelColor = Color.GREEN;
            SwingUtilities.invokeAndWait(panel::repaint);
            expect(robot, centre, Color.GREEN, "a Swing repaint");

            // 2. drawing on the window outside any paint cycle: the flusher's timeout presents it
            SwingUtilities.invokeAndWait(() -> {
                Graphics g = frame.getGraphics();
                g.setColor(Color.BLUE);
                g.fillRect(0, 0, frame.getWidth(), frame.getHeight());
                g.dispose();
            });
            expect(robot, centre, Color.BLUE, "drawing outside a paint cycle");

            // 3. the same, followed by Toolkit.sync: present at once
            SwingUtilities.invokeAndWait(() -> {
                Graphics g = frame.getGraphics();
                g.setColor(Color.YELLOW);
                g.fillRect(0, 0, frame.getWidth(), frame.getHeight());
                g.dispose();
                Toolkit.getDefaultToolkit().sync();
            });
            Color afterSync = robot.getPixelColor(centre.x, centre.y);
            if (!close(afterSync, Color.YELLOW)) {
                throw new RuntimeException("after Toolkit.sync the screen shows " + afterSync + ", not yellow");
            }
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    static Point centreOf(JPanel panel) {
        Point p = panel.getLocationOnScreen();
        return new Point(p.x + panel.getWidth() / 2, p.y + panel.getHeight() / 2);
    }

    /** Waits for the screen pixel to take the colour, up to the timeout. */
    static void expect(Robot robot, Point at, Color expected, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        Color seen = null;
        while (System.currentTimeMillis() < deadline) {
            seen = robot.getPixelColor(at.x, at.y);
            if (close(seen, expected)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new RuntimeException(what + " did not reach the screen within " + TIMEOUT_MS + " ms: " + seen
                + " instead of " + expected);
    }

    static boolean close(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) <= 8 && Math.abs(a.getGreen() - b.getGreen()) <= 8
                && Math.abs(a.getBlue() - b.getBlue()) <= 8;
    }
}
