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
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary Batched glyphs, rects and anti-aliased shapes interleaved on the Skia pipe, as Swing
 *          paints a list or a tab row (a cell's background, its text, a separator, an icon-like
 *          shape): the glyph batch stays open across the rects and shapes that do not touch it,
 *          so a row takes a few glyph batch draws, not one per cell; text drawn over a rect, a
 *          rect drawn over text and a shape drawn over text are composited in the order given,
 *          and the row matches the software loops.
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaKindBatchTest
 */
public class SkiaKindBatchTest {
    static final int W = 900, H = 60;
    static final int CELLS = 30;
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
        System.out.println(CELLS + " cells of rects, text and shapes: " + draws + " glyph batch draws; against the software loops max "
                + max + " levels, " + beyond + " pixels beyond 32");
        if (beyond > 0) {
            throw new RuntimeException("interleaved rects, glyphs and shapes differ from the software loops: " + beyond
                    + " pixels beyond 32 levels (max " + max + ")");
        }
        if (draws > 18) {   // 13 by construction: 12 rects and ovals drawn over their token, and the end
            throw new RuntimeException(draws + " glyph batch draws for " + CELLS + " cells in one text colour: rects and shapes draw the glyph batch");
        }
        System.out.println("PASSED");
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font);
        for (int i = 0; i < CELLS; i++) {
            int x = 4 + i * 29;
            g.setColor(i % 2 == 0 ? new Color(0xE8F0FF) : new Color(0xFFF4E0));
            g.fillRect(x, 6, 27, 18);                     // the cell's background: the text goes over it
            g.setColor(new Color(0x0033B3));
            g.drawString("t" + i, x + 2, 20);
            g.setColor(new Color(0x808080));
            g.fillRect(x, 28, 27, 1);                     // a separator below, touching no glyph
            if (i % 4 == 3) {
                g.setColor(new Color(0xC03030));
                g.fillRect(x + 4, 10, 8, 8);              // over the token: drawn after its glyphs
            }
            if (i % 3 == 0) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x10A040));
                if (i % 6 == 0) {
                    g.fillOval(x + 6, 9, 10, 10);         // over the token
                } else {
                    g.fillOval(x + 8, 34, 12, 12);        // below it, touching no glyph
                }
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            }
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
