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

/*
 * @test
 * @key headful
 * @summary The Metal glyph caches are written from the CPU with replaceRegion
 *          while command buffers that sample other cells of the same texture
 *          may still be running. A frame that draws one set of glyphs is
 *          committed and, before it can complete, a second frame uploads new
 *          glyphs into neighbouring cells; the first frame must come out
 *          exactly as when it is drawn alone. A tiled texture layout, where
 *          an upload is a block conversion that can touch a neighbour's
 *          texels, would show here as damaged glyphs; a linear layout cannot.
 *          The test is a race and a pass is not a proof, a failure is.
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 GlyphCacheConcurrentUploadTest gray
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 GlyphCacheConcurrentUploadTest lcd
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class GlyphCacheConcurrentUploadTest {

    private static final int W = 1600;
    private static final int H = 1000;
    private static final int ITERATIONS = 150;
    private static final int SIZES = 20;

    private static String alphabet;

    public static void main(String[] args) throws Exception {
        boolean lcd = args[0].equals("lcd");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage a = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        VolatileImage b = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        String surface = SurfaceData.getPrimarySurfaceData(a).getClass().getName();
        if (!surface.contains(".metal.")) {
            System.out.println("The Metal pipeline is not active, nothing to test: " + surface);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (char c = 33; c < 127; c++) {
            sb.append(c);
        }
        alphabet = sb.toString();
        int failures = 0;
        int worst = 0;
        long overlapNanos = 0;
        long uploadNanos = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            Font first = new Font(Font.SANS_SERIF, Font.PLAIN, 9 + i % SIZES);
            Font second = new Font(Font.SANS_SERIF, Font.BOLD, 9 + (i + 7) % SIZES);
            // the frame drawn alone, completed before it is read: the reference
            paintHeavy(a, lcd, first);
            BufferedImage reference = a.getSnapshot();
            // the same frame, followed at once by uploads of other glyphs into
            // neighbouring cells of the same cache while it may still be running
            paintHeavy(a, lcd, first);
            long uploadsStart = System.nanoTime();
            for (int k = 0; k < 4; k++) {   // several glyph sets, so uploads keep coming while the frame runs
                paintGlyphs(b, lcd, new Font(Font.SANS_SERIF, Font.BOLD | (k % 2 == 0 ? 0 : Font.ITALIC), 9 + (i + 7 + 5 * k) % SIZES));
            }
            long uploadsDone = System.nanoTime();
            BufferedImage racing = a.getSnapshot();
            long frameDone = System.nanoTime();
            overlapNanos += Math.max(0, frameDone - uploadsDone);
            uploadNanos += uploadsDone - uploadsStart;
            if (a.contentsLost() || b.contentsLost()) {
                throw new RuntimeException("Volatile image contents were lost");
            }
            int differing = 0;
            int max = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int d = maxChannelDifference(reference.getRGB(x, y), racing.getRGB(x, y));
                    if (d > 0) {
                        differing++;
                    }
                    max = Math.max(max, d);
                }
            }
            if (differing > 0) {
                failures++;
                worst = Math.max(worst, max);
                System.out.println("iteration " + i + ": " + differing + " pixels differ, max " + max + " levels");
            }
        }
        System.out.printf("uploads took %.2f ms per iteration; the frame was still running %.2f ms after them%n",
                          uploadNanos / 1e6 / ITERATIONS, overlapNanos / 1e6 / ITERATIONS);
        System.out.println((lcd ? "LCD" : "grayscale") + ": " + failures + " of " + ITERATIONS
                + " racing frames differ from the frame drawn alone" + (failures > 0 ? ", worst " + worst + " levels" : ""));
        if (failures > 0) {
            throw new RuntimeException("Glyph uploads into neighbouring cells changed a running frame in "
                    + failures + " of " + ITERATIONS + " iterations");
        }
        System.out.println("PASSED");
    }

    /** Enough GPU work to keep the frame running for a while, then the glyph set. */
    private static void paintHeavy(VolatileImage image, boolean lcd, Font font) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.05f));
            g.setColor(new Color(0x2040C0));
            for (int i = 0; i < 400; i++) {
                g.fillOval(i % 40, (i / 40) * 3, W - 80, H - 40);
            }
            g.setComposite(AlphaComposite.SrcOver);
            paintGlyphs(g, lcd, font);
        } finally {
            g.dispose();
        }
    }

    private static void paintGlyphs(VolatileImage image, boolean lcd, Font font) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            paintGlyphs(g, lcd, font);
        } finally {
            g.dispose();
        }
    }

    private static void paintGlyphs(Graphics2D g, boolean lcd, Font font) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                               : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        int y = 40;
        for (int row = 0; row < 12; row++) {
            g.drawString(alphabet, 10, y);
            y += font.getSize() + 6;
        }
    }

    static int maxChannelDifference(int p, int q) {
        int r = Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff));
        int g = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(g, b));
    }
}
