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
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.VolatileImage;
import java.util.Arrays;
import javax.swing.SwingUtilities;

import sun.java2d.BufferedSurfaceDataExt;
import sun.java2d.SunGraphics2D;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary The paint cycle's flush handed to the flusher (sun.java2d.skia.asyncPresent) against
 *          what its queued work depends on, each case while a hand-off is held in flight on purpose
 *          (sun.java2d.skia.test.handOffDelay): images drawn and then changed (a cached image's
 *          texture, an untracked image's pixels) are drawn as they were; images released right
 *          after a frame that draws them, explicitly or by the Disposer, are drawn and do not crash
 *          the flusher; font strikes collected while their glyphs are in flight neither; a sync point
 *          waits for the hand-off, and a window is presented once per paint cycle; a hand-off that
 *          fails reports its error at the next sync point, and later frames are drawn.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia java.desktop/sun.awt
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=60 SkiaHandOffTest sources
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=60 SkiaHandOffTest dispose
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=60 -Dsun.java2d.font.reftype=weak SkiaHandOffTest strikes
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=60 -Dawt.window.counters=java2d.native.frames SkiaHandOffTest sync
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.asyncPresent=true -Dsun.java2d.skia.test.handOffDelay=60 -Dsun.java2d.skia.test.handOffError=3 SkiaHandOffTest error
 */
public class SkiaHandOffTest {
    static final int W = 400, H = 200, DELAY = 60;
    static Frame frame;
    static Robot robot;

    interface Paint {
        void paint(Graphics2D g) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new Frame("SkiaHandOffTest");
                frame.setUndecorated(true);
                frame.setBounds(60, 60, W, H);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
            });
            robot.waitForIdle();
            robot.delay(1000);
            if (!SKEnvironment.isSkiaEnabled()) {
                System.out.println("Skia is not available: nothing to test");
                return;
            }
            cycle(g -> { g.setColor(Color.WHITE); g.fillRect(0, 0, W, H); });
            sync();
            switch (args[0]) {
                case "sources" -> sources();
                case "dispose" -> dispose();
                case "strikes" -> strikes();
                case "sync" -> syncAndPresents();
                case "error" -> error();
                default -> throw new IllegalArgumentException(args[0]);
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

    // One paint cycle onto the window, ended as Swing ends it (the window surface's commit): handed off.
    static void cycle(Paint p) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = (Graphics2D) frame.getGraphics();
            try {
                p.paint(g);
            } catch (Exception e) {
                failure[0] = e;
            }
            ((BufferedSurfaceDataExt) ((SunGraphics2D) g).getSurfaceData()).commit();
            g.dispose();
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    static void sync() throws Exception {
        SwingUtilities.invokeAndWait(() -> Toolkit.getDefaultToolkit().sync());
        robot.waitForIdle();
        robot.delay(150);
    }

    static void expect(int x, int y, Color expected, String what) {
        Point p = frame.getLocationOnScreen();
        Color c = robot.getPixelColor(p.x + x, p.y + y);
        if (Math.abs(c.getRed() - expected.getRed()) > 8 || Math.abs(c.getGreen() - expected.getGreen()) > 8
                || Math.abs(c.getBlue() - expected.getBlue()) > 8) {
            throw new RuntimeException(what + ": the window shows " + c + ", expected " + expected);
        }
    }

    static BufferedImage filled(int type, Color c) {
        BufferedImage img = new BufferedImage(20, 20, type);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 20, 20);
        g.dispose();
        return img;
    }

    // Images drawn in a handed-off frame and changed before it ran.
    static void sources() throws Exception {
        BufferedImage cached = filled(BufferedImage.TYPE_INT_RGB, Color.RED);
        for (int i = 0; i < 5; i++) {   // drawn often enough to get its texture copy
            cycle(g -> g.drawImage(cached, 0, 100, null));
        }
        sync();
        cycle(g -> g.drawImage(cached, 0, 0, null));             // in flight with the red texture
        Graphics2D cg = cached.createGraphics();                  // the image changes: its texture is updated
        cg.setColor(Color.BLUE);                                  // when it is drawn next
        cg.fillRect(0, 0, 20, 20);
        cg.dispose();
        cycle(g -> g.drawImage(cached, 40, 0, null));
        // untracked pixels (never cached): read when the copy is queued, so changing them right
        // after the draw, in the same paint cycle (Swing's pattern for an image it paints into),
        // does not change what was drawn
        BufferedImage raw = filled(BufferedImage.TYPE_INT_RGB, Color.RED);
        int[] data = ((DataBufferInt) raw.getRaster().getDataBuffer()).getData();
        cycle(g -> {
            g.drawImage(raw, 80, 0, null);
            Arrays.fill(data, Color.BLUE.getRGB());
            g.drawImage(raw, 120, 0, null);
        });
        // the same for the cached image, its texture updated within the cycle
        BufferedImage cached2 = filled(BufferedImage.TYPE_INT_RGB, Color.RED);
        for (int i = 0; i < 5; i++) {
            cycle(g -> g.drawImage(cached2, 0, 140, null));
        }
        sync();
        cycle(g -> {
            g.drawImage(cached2, 160, 0, null);
            Graphics2D c2 = cached2.createGraphics();
            c2.setColor(Color.BLUE);
            c2.fillRect(0, 0, 20, 20);
            c2.dispose();
            g.drawImage(cached2, 200, 0, null);
        });
        sync();
        expect(170, 10, Color.RED, "cached image, drawn before it changed in the same cycle");
        expect(210, 10, Color.BLUE, "cached image, drawn after it changed in the same cycle");
        expect(10, 10, Color.RED, "cached image, drawn before it changed");
        expect(50, 10, Color.BLUE, "cached image, drawn after it changed");
        expect(90, 10, Color.RED, "untracked image, drawn before it changed");
        expect(130, 10, Color.BLUE, "untracked image, drawn after it changed");
    }

    // Accelerated images released while a frame that draws them is in flight.
    static void dispose() throws Exception {
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        for (int i = 0; i < 20; i++) {
            Color c = new Color(0, 100 + i * 5, 255 - i * 5);
            VolatileImage vi = gc.createCompatibleVolatileImage(20, 20, Transparency.OPAQUE);
            Graphics2D vg = vi.createGraphics();
            vg.setColor(c);
            vg.fillRect(0, 0, 20, 20);
            vg.dispose();
            int x = (i % 10) * 30;
            int y = i < 10 ? 0 : 40;
            cycle(g -> g.drawImage(vi, x, y, null));
            if (i % 2 == 0) {
                vi.flush();   // released at once
            } else {
                System.gc();  // unreachable: released by the Disposer
            }
        }
        System.gc();
        robot.delay(500);
        cycle(g -> { g.setColor(Color.MAGENTA); g.fillRect(0, 100, 20, 20); });
        sync();
        expect(10, 110, Color.MAGENTA, "a frame after the releases");
        expect(9 * 30 + 10, 50, new Color(0, 195, 160), "the last image, released by the Disposer");
    }

    // Font strikes collected while their glyphs are in flight (weakly referenced strikes).
    static void strikes() throws Exception {
        for (int i = 0; i < 40; i++) {
            float size = 9 + i * 0.5f;
            cycle(g -> {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, 60);
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SERIF, Font.PLAIN, 1).deriveFont(size));
                g.drawString("strike " + size + " abcdefghijklmnopqrstuvwxyz", 4, 40);
            });
            System.gc();
        }
        cycle(g -> { g.setColor(Color.MAGENTA); g.fillRect(0, 100, 20, 20); });
        sync();
        expect(10, 110, Color.MAGENTA, "a frame after the strikes were collected");
    }

    // A sync point waits for the hand-off in flight; one present per paint cycle.
    static void syncAndPresents() throws Exception {
        long[] waited = new long[1];
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = (Graphics2D) frame.getGraphics();
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, 20, 20);
            ((BufferedSurfaceDataExt) ((SunGraphics2D) g).getSurfaceData()).commit();
            g.dispose();
            long t0 = System.nanoTime();
            Toolkit.getDefaultToolkit().sync();
            waited[0] = (System.nanoTime() - t0) / 1_000_000;
        });
        System.out.println("the sync after the commit waited " + waited[0] + " ms");
        if (waited[0] < DELAY * 8 / 10) {
            throw new RuntimeException("the sync returned after " + waited[0] + " ms, before the hand-off in flight ran ("
                    + DELAY + " ms)");
        }
        robot.delay(150);
        expect(10, 10, Color.ORANGE, "the frame the sync waited for");
        long before = frames();
        int cycles = 20;
        BufferedImage icon = filled(BufferedImage.TYPE_INT_RGB, Color.GREEN);
        for (int i = 0; i < cycles; i++) {
            final int k = i;
            cycle(g -> {
                g.setColor(k % 2 == 0 ? Color.YELLOW : Color.CYAN);
                g.fillRect(0, 40, W, 40);
                icon.setRGB(k % 20, 0, 0x00ff00 + k);   // a changed image: a flush that waits, mid-cycle
                g.drawImage(icon, 10, 50, null);
                g.fillRect(0, 120, W, 40);
            });
        }
        sync();
        long presents = frames() - before;
        System.out.println(cycles + " paint cycles: " + presents + " presents");
        if (presents > cycles + 2) {
            throw new RuntimeException(presents + " presents for " + cycles + " paint cycles: windows presented mid-cycle");
        }
        expect(10, 130, cycles % 2 == 0 ? Color.CYAN : Color.YELLOW, "the last cycle");
    }

    // A hand-off that fails: its error at the next sync point, later frames drawn.
    static void error() throws Exception {
        cycle(g -> { g.setColor(Color.GRAY); g.fillRect(0, 0, 20, 20); });      // the test's 2nd hand-off
        cycle(g -> { g.setColor(Color.PINK); g.fillRect(40, 0, 20, 20); });     // the 3rd: fails
        Throwable[] thrown = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Toolkit.getDefaultToolkit().sync();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        System.out.println("the sync after the failed hand-off: " + thrown[0]);
        if (!(thrown[0] instanceof InternalError) || !String.valueOf(thrown[0].getMessage()).contains("injected")) {
            throw new RuntimeException("the failed hand-off's error did not reach the next sync point: " + thrown[0]);
        }
        cycle(g -> { g.setColor(Color.MAGENTA); g.fillRect(0, 100, 20, 20); });
        sync();
        expect(10, 110, Color.MAGENTA, "a frame after the failed hand-off");
    }

    static long frames() {
        return sun.awt.AWTAccessor.getWindowAccessor().getCounter(frame, "java2d.native.frames");
    }
}
