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
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import javax.swing.SwingUtilities;

import sun.java2d.BufferedSurfaceDataExt;
import sun.java2d.SunGraphics2D;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary With sun.java2d.skia.asyncPresent=true the flush that ends a window's paint cycle is
 *          handed to the flusher without the EDT waiting for it: after a heavy frame drawn onto a
 *          window, the paint cycle's commit returns at once and the next sync point (Toolkit.sync)
 *          waits for the frame instead; without the property the commit runs the frame and the
 *          sync finds nothing left. Either way the window shows the whole frame after the sync.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true SkiaAsyncPresentTest true
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaAsyncPresentTest false
 */
public class SkiaAsyncPresentTest {
    static final int W = 900, H = 600, ROUNDS = 5;
    static Frame frame;

    public static void main(String[] args) throws Exception {
        boolean asyncExpected = Boolean.parseBoolean(args[0]);
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new Frame("SkiaAsyncPresentTest");
                frame.setUndecorated(true);
                frame.setBounds(50, 50, W, H);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
            });
            robot.waitForIdle();
            robot.delay(1000);
            if (!SKEnvironment.isSkiaEnabled()) {
                System.out.println("Skia is not available: nothing to test");
                return;
            }
            int commitLonger = 0;
            for (int round = 0; round < ROUNDS; round++) {
                Color marker = round % 2 == 0 ? Color.MAGENTA : Color.CYAN;
                long[] times = new long[2];
                SwingUtilities.invokeAndWait(() -> {
                    Graphics2D g = (Graphics2D) frame.getGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, W, H);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                    for (int i = 0; i < 4000; i++) {   // a heavy frame: thousands of glyph lists and rects
                        int x = (i % 20) * 44, y = 12 + (i / 20) * 2 % (H - 40);
                        g.drawString("w" + (i % 97), x, y);
                        g.fillRect(x, y + 1, 30, 1);
                    }
                    g.setColor(marker);
                    g.fillRect(W - 60, H - 60, 50, 50);
                    BufferedSurfaceDataExt sd = (BufferedSurfaceDataExt) ((SunGraphics2D) g).getSurfaceData();
                    long t0 = System.nanoTime();
                    sd.commit();   // the paint cycle's end, as Swing's updateWindows does it
                    long t1 = System.nanoTime();
                    Toolkit.getDefaultToolkit().sync();
                    long t2 = System.nanoTime();
                    times[0] = t1 - t0;
                    times[1] = t2 - t1;
                    g.dispose();
                });
                System.out.printf("round %d: commit %.2f ms, sync after it %.2f ms%n", round, times[0] / 1e6, times[1] / 1e6);
                if (times[0] > times[1]) {
                    commitLonger++;
                }
                robot.waitForIdle();
                robot.delay(300);
                Point p = frame.getLocationOnScreen();
                Color c = robot.getPixelColor(p.x + W - 35, p.y + H - 35);
                if (Math.abs(c.getRed() - marker.getRed()) > 8 || Math.abs(c.getGreen() - marker.getGreen()) > 8
                        || Math.abs(c.getBlue() - marker.getBlue()) > 8) {
                    throw new RuntimeException("round " + round + ": the window shows " + c + ", expected " + marker);
                }
            }
            // handed off: the sync waits for the frame (commit shorter in most rounds); run at once: the reverse
            boolean asyncSeen = commitLonger <= ROUNDS / 2;
            System.out.println("commit longer than the sync in " + commitLonger + " of " + ROUNDS + " rounds");
            if (asyncSeen != asyncExpected) {
                throw new RuntimeException("the paint cycle's flush " + (asyncSeen ? "was" : "was not")
                        + " handed off, expected " + (asyncExpected ? "handed off" : "run at once"));
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
}
