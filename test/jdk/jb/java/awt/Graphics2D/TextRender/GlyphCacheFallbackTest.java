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
 * @summary A frame that needs more distinct glyphs than the Metal glyph
 *          cache has cells cannot evict (every cell is referenced by the
 *          frame being recorded) and falls back to replacing the cache
 *          texture, which goes back to the texture pool and is taken again
 *          by the next cache. Ten such frames must render identically, and
 *          text drawn afterwards must match text drawn before.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheFallbackTest gray
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheFallbackTest lcd
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

public class GlyphCacheFallbackTest {

    private static final int W = 1400;
    private static final int H = 1000;
    private static final int SIZES = 32;   // 32 x 94 = 3,008 distinct glyphs, three times the cells

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
        String alphabet = sb.toString();
        Font[] fonts = new Font[SIZES];
        for (int i = 0; i < SIZES; i++) {
            fonts[i] = new Font(Font.SANS_SERIF, i % 2 == 0 ? Font.PLAIN : Font.BOLD, 8 + i);
        }
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        VolatileImage small = gc.createCompatibleVolatileImage(600, 60, Transparency.OPAQUE);

        drawLine(small, lcd, fonts[4], alphabet.substring(0, 50));
        BufferedImage smallBefore = small.getSnapshot();

        BufferedImage first = null;
        for (int frame = 0; frame < 10; frame++) {
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, H);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                   lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                       : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                int y = 20;
                for (Font font : fonts) {
                    g.setFont(font);
                    g.drawString(alphabet, 10, y);
                    y += font.getSize() + 4;
                    if (y > H - 20) {
                        y = 20;
                    }
                }
            } finally {
                g.dispose();
            }
            BufferedImage snapshot = image.getSnapshot();
            if (first == null) {
                first = snapshot;
            } else {
                int diff = countDifferentPixels(first, snapshot);
                if (diff != 0) {
                    throw new RuntimeException("Frame " + frame + " differs from frame 0 after a glyph cache"
                                               + " replacement: " + diff + " pixels");
                }
            }
        }

        drawLine(small, lcd, fonts[4], alphabet.substring(0, 50));
        int diff = countDifferentPixels(smallBefore, small.getSnapshot());
        if (diff != 0) {
            throw new RuntimeException("Text drawn after the glyph cache replacements differs from text drawn"
                                       + " before: " + diff + " pixels");
        }
        if (image.contentsLost() || small.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        System.out.println("PASSED");
    }

    private static void drawLine(VolatileImage image, boolean lcd, Font font, String text) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                   : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(text, 10, 40);
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
