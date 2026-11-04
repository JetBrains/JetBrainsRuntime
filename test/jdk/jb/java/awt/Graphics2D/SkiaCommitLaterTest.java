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
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Robot;
import java.awt.Toolkit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import sun.java2d.BufferedSurfaceDataExt;
import sun.java2d.SunGraphics2D;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary With sun.java2d.skia.asyncPresent the weaker guarantee is opt-in: a window surface's
 *          commit() still runs the paint cycle's flush before it returns, commitLater() hands it to
 *          the flusher, Swing's repaint cycle ends with commitLater() (RepaintManager, through the
 *          window's updateWindowLater), and AWT's own paint events (expose, resize) with commit().
 *          Each case with a hand-off held in flight (sun.java2d.skia.test.handOffDelay), so whether
 *          a flush ran before the call returned shows in the backend's flush count.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=300 SkiaCommitLaterTest
 */
public class SkiaCommitLaterTest {
    static Frame awtFrame;
    static JFrame swingFrame;
    static volatile long flushesAtPaint, flushesAfterPaint;
    static volatile CountDownLatch painted;

    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                awtFrame = new Frame("SkiaCommitLaterTest AWT") {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(Color.ORANGE);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        afterPaint();
                    }

                    @Override
                    public void update(Graphics g) {
                        paint(g);
                    }
                };
                awtFrame.setUndecorated(true);
                awtFrame.setBounds(60, 60, 200, 150);
                awtFrame.setVisible(true);
                swingFrame = new JFrame("SkiaCommitLaterTest Swing");
                swingFrame.setUndecorated(true);
                swingFrame.setBounds(300, 60, 200, 150);
                swingFrame.add(new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        g.setColor(Color.CYAN);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        afterPaint();
                    }
                });
                swingFrame.setVisible(true);
            });
            robot.waitForIdle();
            robot.delay(1500);
            if (!SKEnvironment.isSkiaEnabled()) {
                System.out.println("Skia is not available: nothing to test");
                return;
            }
            Toolkit.getDefaultToolkit().sync();

            // commit(): the flush has run when it returns
            long[] counts = new long[2];
            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = (Graphics2D) awtFrame.getGraphics();
                g.setColor(Color.RED);
                g.fillRect(0, 0, 20, 20);
                long before = SKEnvironment.flushes();
                ((BufferedSurfaceDataExt) ((SunGraphics2D) g).getSurfaceData()).commit();
                counts[0] = SKEnvironment.flushes() - before;
                g.dispose();
            });
            System.out.println("commit(): " + counts[0] + " flushes before it returned");
            if (counts[0] == 0) {
                throw new RuntimeException("commit() returned before its flush ran: it handed the flush off");
            }
            Toolkit.getDefaultToolkit().sync();

            // commitLater(): handed off, the flush has not run when it returns
            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = (Graphics2D) awtFrame.getGraphics();
                g.setColor(Color.BLUE);
                g.fillRect(0, 0, 20, 20);
                long before = SKEnvironment.flushes();
                ((BufferedSurfaceDataExt) ((SunGraphics2D) g).getSurfaceData()).commitLater();
                counts[1] = SKEnvironment.flushes() - before;
                g.dispose();
            });
            System.out.println("commitLater(): " + counts[1] + " flushes before it returned");
            if (counts[1] != 0) {
                throw new RuntimeException("commitLater() ran its flush before it returned: not handed off");
            }
            Toolkit.getDefaultToolkit().sync();

            // Swing's repaint cycle: handed off (nothing flushed between the paint and the next event)
            long swing = paintCycle(() -> swingFrame.getContentPane().repaint());
            System.out.println("Swing repaint: " + swing + " flushes between the paint and the next event");
            if (swing != 0) {
                throw new RuntimeException("Swing's repaint cycle ran its flush before the next event: not handed off");
            }
            // AWT's paint event on a heavyweight window: run before the next event
            long awt = paintCycle(() -> awtFrame.repaint());
            System.out.println("AWT paint event: " + awt + " flushes between the paint and the next event");
            if (awt == 0) {
                throw new RuntimeException("AWT's paint event handed its flush off");
            }
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (awtFrame != null) {
                    awtFrame.dispose();
                }
                if (swingFrame != null) {
                    swingFrame.dispose();
                }
            });
        }
    }

    // At the end of a paint: the flush count, and once the paint's event (with its paint cycle's end) is
    // over, the count again (an event queued now runs after it).
    static void afterPaint() {
        CountDownLatch latch = painted;
        if (latch == null) {
            return;
        }
        painted = null;
        flushesAtPaint = SKEnvironment.flushes();
        EventQueue.invokeLater(() -> {
            flushesAfterPaint = SKEnvironment.flushes();
            latch.countDown();
        });
    }

    static long paintCycle(Runnable repaint) throws Exception {
        Toolkit.getDefaultToolkit().sync();
        CountDownLatch latch = new CountDownLatch(1);
        painted = latch;
        SwingUtilities.invokeLater(repaint);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("no paint");
        }
        Toolkit.getDefaultToolkit().sync();
        return flushesAfterPaint - flushesAtPaint;
    }
}
