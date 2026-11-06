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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary Antialiased shapes drawn by Skia between batched glyphs (Skia's analytic shapes: rounded rects, ovals,
 *          their outlines, polygons; and antialiased lines), as a tree or a tab row draws its badges, frames and
 *          markers: a shape that touches no pending batch is drawn ahead of the batches under its clip, so a row of
 *          tokens in one colour takes a few glyph batch draws, not one per shape. Shapes drawn over their token
 *          cover it, shapes cut by their cell's clip are cut, and the row is pixel-identical to the old order (every batch
 *          drawn before a shape).
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaShapeBatchTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan SkiaShapeBatchTest
 */
public class SkiaShapeBatchTest {
    static final int W = 960, H = 64;
    static final int CELLS = 32;
    static final int BATCH_DRAWS = 5;   // SKGlyphCacheStat order: cached, staged, evicted, uploads, uncached, batch draws
    static final int MAX_DRAWS = 8;     // 4 on Dawn and Vulkan (2026-11-06): four shapes drawn over their token; 24 in the old order

    public static void main(String[] args) throws Exception {
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        if (!(Boolean) env.getMethod("isSkiaEnabled").invoke(null)) {
            System.out.println("Skia is not available: nothing to test");
            return;
        }
        Method pastBatches = env.getMethod("setCanvasOpsPastBatches", boolean.class);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        long draws, eagerDraws;
        BufferedImage actual, eager;
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font);   // stages every glyph
            vi.getSnapshot();
            pastBatches.invoke(null, false);   // the old order: every batch drawn before a shape
            long before = stats()[BATCH_DRAWS];
            paint(vi.createGraphics(), font);
            eager = vi.getSnapshot();
            eagerDraws = stats()[BATCH_DRAWS] - before;
            pastBatches.invoke(null, true);
            before = stats()[BATCH_DRAWS];
            paint(vi.createGraphics(), font);
            actual = vi.getSnapshot();
            draws = stats()[BATCH_DRAWS] - before;
        } while (vi.contentsLost());

        int differing = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (eager.getRGB(x, y) != actual.getRGB(x, y)) {
                    differing++;
                }
            }
        }
        System.out.println(CELLS + " cells of text and shapes: " + draws + " glyph batch draws (" + eagerDraws
                + " with every batch drawn before a shape); " + differing + " pixels differing from that order");
        if (differing > 0) {
            throw new RuntimeException("shapes drawn ahead of the batches change the picture: " + differing + " pixels");
        }
        if (draws > MAX_DRAWS) {
            throw new RuntimeException(draws + " glyph batch draws for " + CELLS + " cells in one text colour: shapes touching no glyph draw the batch");
        }
        System.out.println("PASSED");
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        for (int i = 0; i < CELLS; i++) {
            int x = 4 + i * 29;
            g.setClip(x, 2, 27, i % 5 == 2 ? 44 : 60);   // a cell's clip; every fifth cuts its lowest shape
            g.setColor(new Color(0x0033B3));
            g.drawString("t" + i, x + 2, 18);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x10A040));
            switch (i % 4) {
                case 0 -> g.fillRoundRect(x + 2, 26, 22, 10, 6, 6);                 // a badge below the token
                case 1 -> { g.setStroke(new BasicStroke(1.5f)); g.drawOval(x + 4, 26, 16, 14); g.setStroke(new BasicStroke()); }
                case 2 -> g.fillPolygon(new Polygon(new int[] {x + 3, x + 22, x + 12}, new int[] {30, 30, 48}, 3));
                default -> g.drawLine(x + 2, 40, x + 24, 30);                          // an antialiased line
            }
            if (i % 8 == 7) {
                g.setColor(new Color(0xC03030));
                g.fillOval(x + 6, 7, 10, 10);   // over the token: drawn after its glyphs
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
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
