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
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary Image blits between batched glyphs on the Skia pipe: a row of tokens, each in its
 *          own cell clip with an icon beside it (an accelerated image and an uploaded one in
 *          turn), as Swing paints a tree or a tab row, takes a few glyph batch draws, not one
 *          per icon: an icon that touches no pending glyph is drawn ahead of the batch. Icons
 *          drawn over their token cover it, icons cut by their clip are cut, and the whole row
 *          matches the software loops.
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaBlitBatchTest
 */
public class SkiaBlitBatchTest {
    static final int W = 900, H = 60;
    static final int TOKENS = 30;
    static final int ICON = 12;
    static final int BATCH_DRAWS = 5;   // SKGlyphCacheStat order: cached, staged, evicted, uploads, uncached, batch draws

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage pixels = icon();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        VolatileImage accelerated = gc.createCompatibleVolatileImage(ICON, ICON, Transparency.TRANSLUCENT);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        long draws;
        BufferedImage actual;
        do {
            vi.validate(gc);
            accelerated.validate(gc);
            Graphics2D ig = accelerated.createGraphics();
            ig.setComposite(java.awt.AlphaComposite.Src);
            ig.drawImage(pixels, 0, 0, null);
            ig.dispose();
            paint(vi.createGraphics(), font, accelerated, pixels);   // stages every glyph
            vi.getSnapshot();
            long before = stats()[BATCH_DRAWS];
            paint(vi.createGraphics(), font, accelerated, pixels);
            actual = vi.getSnapshot();
            draws = stats()[BATCH_DRAWS] - before;
        } while (vi.contentsLost() || accelerated.contentsLost());
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font, pixels, pixels);

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
        System.out.println(TOKENS + " tokens with an icon each: " + draws + " glyph batch draws; against the software loops max "
                + max + " levels, " + beyond + " pixels beyond 32");
        if (beyond > 0) {
            throw new RuntimeException("icons between glyphs differ from the software loops: " + beyond
                    + " pixels beyond 32 levels (max " + max + ")");
        }
        if (draws > 12) {
            throw new RuntimeException(draws + " glyph batch draws for " + TOKENS + " tokens in one colour: the icons draw the batches");
        }
        System.out.println("PASSED");
    }

    // Opaque but for a translucent bottom row, in colours far from the text's and the background's.
    static BufferedImage icon() {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                int argb = (x + y) % 4 < 2 ? 0xffe07000 : 0xff10b040;
                img.setRGB(x, y, y == ICON - 1 ? (argb & 0x00ffffff) | 0x80000000 : argb);
            }
        }
        return img;
    }

    static void paint(Graphics2D g, Font font, Image accelerated, Image pixels) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font);
        g.setColor(new Color(0x0033B3));
        for (int i = 0; i < TOKENS; i++) {
            int x = 4 + i * 29;
            // a cell's clip; every third one cuts the icon below the token
            g.setClip(x, 4, 27, i % 3 == 0 ? 34 : 50);
            g.drawString("t" + i, x, 20);
            Image icon = i % 2 == 0 ? accelerated : pixels;
            if (i % 5 == 4) {
                g.drawImage(icon, x + 2, 8, null);    // over the token: drawn after its glyphs
            } else {
                g.drawImage(icon, x + 4, 28, null);   // below it, touching no glyph
            }
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
