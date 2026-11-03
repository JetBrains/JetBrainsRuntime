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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary LCD text drawn in two passes (sun.java2d.skia.lcd=twopass) keeps its
 *          glyph batches open across colour changes: a line of syntax-coloured
 *          tokens, one glyph list each in one of four colours (an editor line),
 *          takes a few batch draws per colour, not one per token, and still
 *          matches the software loops, also where tokens of different colours
 *          overlap (such glyphs are drawn in the order they were given).
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass SkiaLcdColorBatchTest
 */

/*
 * @test id=vulkan
 * @summary The same on Graphite's native Vulkan backend (Windows), where a kPlus second pass was
 *          blended in the shader from an input attachment and the overlap layers lost it.
 * @requires (os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan -Dsun.java2d.skia.lcd=twopass SkiaLcdColorBatchTest
 */
public class SkiaLcdColorBatchTest {
    static final int W = 900, H = 60;
    static final int TOKENS = 40;
    static final Color[] COLORS = { new Color(0x0033B3), new Color(0x871094), new Color(0x067D17), new Color(0x080808) };
    static final String[] WORDS = { "final", "Builder", "name", "=", "hero", ".", "with", "(", "\"x\"", ")", ";" };
    static final int BATCH_DRAWS = 5;   // SKGlyphCacheStat order: cached, staged, evicted, uploads, uncached, batch draws

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        long draws;
        BufferedImage actual;
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font);   // stages every glyph
            vi.getSnapshot();
            long before = stats()[BATCH_DRAWS];
            paint(vi.createGraphics(), font);
            actual = vi.getSnapshot();
            draws = stats()[BATCH_DRAWS] - before;
        } while (vi.contentsLost());
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font);

        int beyond = 0, max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = reference.getRGB(x, y), q = actual.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff)),
                        Math.max(Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff)), Math.abs((p & 0xff) - (q & 0xff))));
                max = Math.max(max, d);
                if (d > 32) {
                    beyond++;
                }
            }
        }
        System.out.println(TOKENS + " tokens in " + COLORS.length + " colours (plus " + (2 * COLORS.length)
                + " overlapping): " + draws + " glyph batch draws; against the software loops max " + max
                + " levels, " + beyond + " pixels beyond 32");
        if (beyond > 0) {
            throw new RuntimeException("LCD text differs from the software loops: " + beyond + " pixels beyond 32 levels, max " + max);
        }
        // two passes per batch draw at most two batch draws per colour: one for the line,
        // one for the overlapping pairs, whose second token waits for the first
        if (draws > 2L * 2 * COLORS.length) {
            throw new RuntimeException(draws + " glyph batch draws for " + COLORS.length + " colours: the batches break at colour changes");
        }
        System.out.println("PASSED");
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int x = 4;
        for (int i = 0; i < TOKENS; i++) {
            String word = WORDS[i % WORDS.length];
            g.setColor(COLORS[i % COLORS.length]);
            g.drawString(word, x, 20);
            x += fm.stringWidth(word) + (i % 3 == 0 ? fm.charWidth(' ') : 0);
            if (x > W - 80) {
                break;
            }
        }
        // tokens of different colours whose ink overlaps: the second is drawn over the first
        x = 4;
        for (int i = 0; i < 2 * COLORS.length; i++) {
            String word = "Wm" + i;
            g.setColor(COLORS[i % COLORS.length]);
            g.drawString(word, x, 45);
            x += fm.stringWidth(word) - 2;
        }
        g.dispose();
    }

    static long[] stats() throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphCacheStats");
        long[] s = (long[]) m.invoke(null);
        if (s.length <= BATCH_DRAWS) {
            throw new RuntimeException("the backend counts no glyph batch draws (" + s.length + " statistics)");
        }
        return s;
    }
}
