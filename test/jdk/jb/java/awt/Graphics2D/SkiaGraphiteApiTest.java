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
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary On Windows Graphite renders through Dawn unless
 *          sun.java2d.skia.graphiteApi=vulkan asks for native Vulkan (an
 *          evaluation option); a value the platform does not offer (metal on
 *          Windows) leaves the default. On either API a window shows what is
 *          painted, also after a resize (a new swap chain). When Skia is
 *          unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaGraphiteApiTest Dawn
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=dawn -Dsun.java2d.uiScale=1 SkiaGraphiteApiTest Dawn
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan -Dsun.java2d.uiScale=1 SkiaGraphiteApiTest Vulkan
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=metal -Dsun.java2d.uiScale=1 SkiaGraphiteApiTest Dawn
 */
public class SkiaGraphiteApiTest {
    static final int TIMEOUT_MS = 3000;

    static volatile Color panelColor = Color.RED;

    public static void main(String[] args) throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        String description = SKEnvironment.getDescription();
        System.out.println(description);
        if (!description.contains(" Graphite/" + args[0] + " on ")) {
            throw new RuntimeException("expected Graphite on " + args[0] + ": " + description);
        }

        Robot robot = new Robot();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<JPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("SkiaGraphiteApiTest");
            f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            f.setUndecorated(true);
            JPanel p = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(panelColor);
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.BLACK);
                    g.fillRect(0, 0, 10, 10);   // the client area's origin lands at the window's
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
            expect(robot, centreOf(panel), Color.RED, "the first paint");
            expect(robot, originOf(panel), Color.BLACK, "the first paint's origin");

            panelColor = Color.GREEN;
            SwingUtilities.invokeAndWait(panel::repaint);
            expect(robot, centreOf(panel), Color.GREEN, "a repaint");

            panelColor = Color.BLUE;
            SwingUtilities.invokeAndWait(() -> frame.setSize(420, 260));
            robot.waitForIdle();
            expect(robot, centreOf(panel), Color.BLUE, "a repaint after a resize");
            expect(robot, originOf(panel), Color.BLACK, "the origin after a resize");
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    static Point centreOf(JPanel panel) throws Exception {
        AtomicReference<Point> at = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Point p = panel.getLocationOnScreen();
            at.set(new Point(p.x + panel.getWidth() / 2, p.y + panel.getHeight() / 2));
        });
        return at.get();
    }

    static Point originOf(JPanel panel) throws Exception {
        AtomicReference<Point> at = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Point p = panel.getLocationOnScreen();
            at.set(new Point(p.x + 5, p.y + 5));
        });
        return at.get();
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
