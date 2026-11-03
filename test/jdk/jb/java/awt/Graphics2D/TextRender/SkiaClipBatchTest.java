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
 * @summary Batched glyphs and rects survive rect clip changes on the Skia pipe: a row of
 *          tokens, each drawn under its own clip as Swing paints cells (some clips cut through
 *          glyphs), takes a few glyph batch draws, not one per clip; the glyphs and the filled
 *          rects under changing clips (some clips cutting them) match the software loops, the
 *          rects exactly, so nothing is drawn outside its clip or lost inside it.
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaClipBatchTest
 */
public class SkiaClipBatchTest {
    static final int W = 900, H = 80;
    static final int TOKENS = 40;
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

        int textBeyond = 0, textMax = 0, rectDiff = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = reference.getRGB(x, y), q = actual.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff)),
                        Math.max(Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff)), Math.abs((p & 0xff) - (q & 0xff))));
                if (y < 40) {
                    textMax = Math.max(textMax, d);
                    if (d > 32) {
                        textBeyond++;
                    }
                } else if (d != 0) {
                    rectDiff++;
                }
            }
        }
        System.out.println(TOKENS + " clipped tokens: " + draws + " glyph batch draws; text against the software loops max "
                + textMax + " levels, " + textBeyond + " pixels beyond 32; clipped rects " + rectDiff + " pixels differ");
        if (textBeyond > 0 || rectDiff > 0) {
            throw new RuntimeException("clipped drawing differs from the software loops: text " + textBeyond
                    + " pixels beyond 32 levels (max " + textMax + "), rects " + rectDiff + " pixels");
        }
        if (draws > 4) {
            throw new RuntimeException(draws + " glyph batch draws for " + TOKENS + " tokens in one colour: the batches break at clip changes");
        }
        System.out.println("PASSED");
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font);
        g.setColor(new Color(0x0033B3));
        FontMetrics fm = g.getFontMetrics();
        int x = 4;
        for (int i = 0; i < TOKENS && x < W - 60; i++) {
            String word = "tok" + i;
            int w = fm.stringWidth(word);
            // a cell's clip around the token; every third one cuts the token's last glyph in half
            int clipW = i % 3 == 0 ? w - fm.charWidth('0') / 2 : w + 2;
            g.setClip(x, 4, clipW, 22);
            g.drawString(word, x, 20);
            x += w + fm.charWidth(' ');
        }
        // filled rects under changing clips, some cut by their clip
        g.setColor(new Color(0x871094));
        for (int i = 0; i < 30; i++) {
            int rx = 4 + i * 29;
            g.setClip(rx + (i % 4 == 0 ? 5 : 0), 44 + (i % 5 == 0 ? 6 : 0), 20, 30);
            g.fillRect(rx, 46, 24, 26);
        }
        g.setClip(null);
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
