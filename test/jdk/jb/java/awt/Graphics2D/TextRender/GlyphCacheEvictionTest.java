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
 * @summary The Metal glyph caches evict cells one at a time once the
 *          texture is full. Glyphs drawn before the cache filled up must
 *          render identically after thousands of other glyphs went through
 *          the cache, including a frame that needs more distinct glyphs
 *          than the cache has cells (the wholesale fallback), in grayscale
 *          and in LCD mode. The frame time under cache pressure is printed
 *          for information; it is not asserted, since the cost of a cache
 *          replacement is small next to the frame and varies with the GPU.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheEvictionTest gray
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheEvictionTest lcd
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

public class GlyphCacheEvictionTest {

    private static final int W = 1400;
    private static final int H = 1000;
    // 94 printable ASCII glyphs per font; 32 sizes x 94 = 3,008 distinct glyph images,
    // about three times the 1,024 cells of a 1024x1024 cache of 32x32 cells
    private static final int SIZES = 32;
    private static final int SIZES_PER_FRAME = 6;
    private static final int FRAMES = 40;
    private static final int FULL_FRAME = 20;

    private static String alphabet;
    private static Font[] fonts;

    public static void main(String[] args) throws Exception {
        boolean lcd = args.length > 0 && args[0].equals("lcd");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            System.out.println("The Metal pipeline is not active, nothing to test: " + gc.getClass());
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (char c = 33; c < 127; c++) {
            sb.append(c);
        }
        alphabet = sb.toString();
        fonts = new Font[SIZES];
        for (int i = 0; i < SIZES; i++) {
            fonts[i] = new Font(Font.SANS_SERIF, i % 2 == 0 ? Font.PLAIN : Font.BOLD, 8 + i);
        }
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);

        // the reference: the first sizes, drawn into a cache that has room for them
        drawFrame(image, lcd, 0, SIZES_PER_FRAME);
        BufferedImage reference = image.getSnapshot();

        // churn: every frame draws other sizes; frame FULL_FRAME draws all of them at once
        for (int frame = 1; frame < FRAMES; frame++) {
            int first = (frame * SIZES_PER_FRAME) % SIZES;
            int count = frame == FULL_FRAME ? SIZES : SIZES_PER_FRAME;
            drawFrame(image, lcd, first, count);
        }

        // the reference glyphs have been evicted and re-cached by now
        drawFrame(image, lcd, 0, SIZES_PER_FRAME);
        BufferedImage after = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        int diff = countDifferentPixels(reference, after);
        if (diff != 0) {
            throw new RuntimeException((lcd ? "LCD" : "Grayscale")
                    + " text changed after glyph cache eviction: " + diff + " pixels differ");
        }

        // frame time under cache pressure, for information
        int sizesForPressure = 16;   // 16 x 94 = 1,504 distinct glyphs per frame
        for (int i = 0; i < 10; i++) {
            drawFrame(image, lcd, 0, sizesForPressure);   // warm-up
        }
        long[] times = new long[30];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            drawFrame(image, lcd, 0, sizesForPressure);
            image.getSnapshot().getRGB(0, 0);   // completes the frame
            times[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(times);
        double medianMs = times[times.length / 2] / 1e6;
        System.out.printf("%s: %d distinct glyphs per frame, median %.2f ms per frame%n",
                          lcd ? "LCD" : "grayscale", sizesForPressure * alphabet.length(), medianMs);
        System.out.println("PASSED");
    }

    /** Draws the alphabet in `count` font sizes starting at `first`, wrapping around the sizes. */
    private static void drawFrame(VolatileImage image, boolean lcd, int first, int count) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                   : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            int y = 20;
            for (int i = 0; i < count; i++) {
                Font font = fonts[(first + i) % SIZES];
                g.setFont(font);
                g.drawString(alphabet.substring(0, 60), 10, y);
                g.drawString(alphabet.substring(34), 10, y + font.getSize() + 2);
                y += 2 * font.getSize() + 8;
                if (y > H - 20) {
                    y = 20;   // frames with many sizes draw over earlier lines; fine for a cache test
                }
            }
        } finally {
            g.dispose();
        }
    }

    static int countDifferentPixels(BufferedImage a, BufferedImage b) {
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return diff;
    }
}
