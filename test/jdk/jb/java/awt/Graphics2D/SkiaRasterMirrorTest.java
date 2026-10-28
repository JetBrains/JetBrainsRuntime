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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary An INT_ARGB BufferedImage on the Skia raster surface is drawn through a
 *          premultiplied mirror the drawing thread keeps between operations. Whatever
 *          else writes the pixels in between — a Java-side setRGB, the software loops
 *          (a thin line), a copy blit, a raster operation on another thread, the
 *          colour-only rectangle store — must be seen by the next antialiased
 *          operation: a translucent fill over the written pixels blends with them,
 *          never with a stale mirror.
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterMirrorTest
 */
public class SkiaRasterMirrorTest {
    static final int W = 200, H = 120;
    static final Color BLUE = new Color(20, 40, 220);

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(220, 30, 30)); g.fill(new Ellipse2D.Double(10, 10, 180, 100));   // Skia, through the mirror

        // 1. a Java-side write
        for (int y = 30; y < 40; y++) for (int x = 30; x < 50; x++) img.setRGB(x, y, BLUE.getRGB());
        // 2. the loops: a thin non-antialiased line
        Graphics2D loops = img.createGraphics();
        loops.setColor(BLUE); loops.drawLine(60, 35, 80, 35); loops.dispose();
        // 3. a copy blit of a blue image (the loops' Blit)
        BufferedImage blue = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = blue.createGraphics(); bg.setColor(BLUE); bg.fillRect(0, 0, 20, 10); bg.dispose();
        g.drawImage(blue, 90, 30, null);
        // 4. a raster operation on another thread: the colour-only rectangle store
        Thread other = new Thread(() -> { Graphics2D og = img.createGraphics(); og.setColor(BLUE); og.fillRect(120, 30, 20, 10); og.dispose(); });
        other.start(); other.join();
        // 5. the colour-only store on this thread
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(BLUE); g.fillRect(150, 30, 20, 10);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // the translucent antialiased fill over everything: Skia again, through the mirror
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(new Color(30, 200, 30));
        g.fill(new Rectangle2D.Double(20, 20, 170, 80));
        g.dispose();

        int expected = blend(BLUE, new Color(30, 200, 30), 0.5f);
        check(img, 40, 35, expected, "a Java-side setRGB");
        check(img, 70, 35, expected, "the loops' thin line");
        check(img, 100, 35, expected, "a copy blit");
        check(img, 130, 35, expected, "a raster store on another thread");
        check(img, 160, 35, expected, "the colour-only store on this thread");
        System.out.println("PASSED");
    }

    static int blend(Color under, Color over, float alpha) {
        int r = Math.round(under.getRed() + (over.getRed() - under.getRed()) * alpha);
        int gg = Math.round(under.getGreen() + (over.getGreen() - under.getGreen()) * alpha);
        int b = Math.round(under.getBlue() + (over.getBlue() - under.getBlue()) * alpha);
        return 0xff000000 | r << 16 | gg << 8 | b;
    }

    static void check(BufferedImage img, int x, int y, int expected, String what) {
        int p = img.getRGB(x, y);
        int d = 0;
        for (int shift = 0; shift <= 16; shift += 8) d = Math.max(d, Math.abs(((p >> shift) & 255) - ((expected >> shift) & 255)));
        System.out.printf("%s: pixel (%d,%d) %08x, expected %08x, max %d levels%n", what, x, y, p, expected, d);
        if (d > 3) {
            throw new RuntimeException(what + " was not seen by the next operation: " + Integer.toHexString(p) + " instead of " + Integer.toHexString(expected));
        }
    }
}
