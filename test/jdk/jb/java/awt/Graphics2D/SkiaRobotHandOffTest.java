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
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary With sun.java2d.skia.asyncPresent, Swing's repaint cycle hands its flush to the flusher and
 *          returns; a Robot screen read that follows (getPixelColor, createScreenCapture) still sees the
 *          frame: Robot syncs the toolkit before it reads the screen, and the Skia queue's sync waits
 *          for a hand-off in flight. Each frame's hand-off is held 300 ms
 *          (sun.java2d.skia.test.handOffDelay), so a read that does not wait returns before the frame's
 *          flush ran (the backend's flush count) and reads the previous frame's colour.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=300 SkiaRobotHandOffTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=300 SkiaRobotHandOffTest
 */
public class SkiaRobotHandOffTest {
    static final Color[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA, Color.YELLOW, Color.CYAN};
    static final int ROUNDS = 12;
    static JFrame frame;
    static JPanel panel;
    static volatile Color color = Color.WHITE;
    static volatile long flushesAtPaint;
    static volatile CountDownLatch painted;

    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("SkiaRobotHandOffTest");
                frame.setUndecorated(true);
                frame.setBounds(100, 100, 240, 180);
                panel = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        g.setColor(color);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        CountDownLatch latch = painted;
                        if (latch != null) {
                            painted = null;
                            // after this event, i.e. after the paint cycle's end (commitLater) handed the frame off
                            EventQueue.invokeLater(() -> {
                                flushesAtPaint = SKEnvironment.flushes();
                                latch.countDown();
                            });
                        }
                    }
                };
                frame.add(panel);
                frame.setVisible(true);
            });
            robot.waitForIdle();
            robot.delay(1500);
            if (!SKEnvironment.isSkiaEnabled()) {
                System.out.println("Skia is not available: nothing to test");
                return;
            }
            Point[] center = new Point[1];
            SwingUtilities.invokeAndWait(() -> {
                Point p = panel.getLocationOnScreen();
                center[0] = new Point(p.x + panel.getWidth() / 2, p.y + panel.getHeight() / 2);
            });
            int waitedOut = 0, stale = 0;
            for (int round = 0; round < ROUNDS; round++) {
                Toolkit.getDefaultToolkit().sync();
                robot.delay(50);
                Color want = COLORS[round % COLORS.length];
                CountDownLatch latch = new CountDownLatch(1);
                painted = latch;
                color = want;
                SwingUtilities.invokeLater(() -> panel.repaint());
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new RuntimeException("no paint");
                }
                long before = flushesAtPaint;
                Color seen;
                boolean capture = round % 2 == 1;
                if (capture) {
                    BufferedImage img = robot.createScreenCapture(new Rectangle(center[0].x, center[0].y, 1, 1));
                    seen = new Color(img.getRGB(0, 0));
                } else {
                    seen = robot.getPixelColor(center[0].x, center[0].y);
                }
                long ran = SKEnvironment.flushes() - before;
                boolean match = seen.getRGB() == want.getRGB();
                System.out.printf("round %d (%s): %d flushes before the read returned, read %s, want %s%n", round,
                        capture ? "createScreenCapture" : "getPixelColor", ran, hex(seen), hex(want));
                if (ran == 0) {
                    waitedOut++;
                }
                if (!match) {
                    stale++;
                }
            }
            if (waitedOut > 0) {
                throw new RuntimeException(waitedOut + " of " + ROUNDS
                        + " screen reads returned before the frame's handed-off flush ran");
            }
            if (stale > 0) {
                throw new RuntimeException(stale + " of " + ROUNDS + " screen reads saw an earlier frame");
            }
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

    static String hex(Color c) {
        return String.format("%06x", c.getRGB() & 0xffffff);
    }
}
