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
 * @summary The Skia pipe's glyph caches come in size buckets (cells of 16, 32, 64 and
 *          128 pixels), so glyph images up to 128 pixels are cached: text at 40 pt
 *          (40-pixel glyphs, the 64 bucket), at 80 pt (the 128 bucket) and a line
 *          mixing 10 pt and 40 pt all hit the caches on their second paint, with no
 *          glyph drawn uncached and the result identical to the first paint. Before
 *          the buckets any glyph over 32 pixels was uploaded and drawn on its own
 *          every frame, which on a 2x display is every glyph above 16 pt.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaGlyphCacheBucketsTest
 */
public class SkiaGlyphCacheBucketsTest {
    static final String TEXT = "Hamburgefonts Wg";
    static final int W = 900, H = 240;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage first, second;
        long[] delta;
        do {
            vi.validate(gc);
            paint(vi);
            first = vi.getSnapshot();
            long[] a = stats();
            paint(vi);
            second = vi.getSnapshot();
            long[] b = stats();
            delta = new long[a.length];
            for (int i = 0; i < a.length; i++) delta[i] = b[i] - a[i];
        } while (vi.contentsLost());
        final int glyphs = 3 * TEXT.replace(" ", "").length();
        System.out.println("second paint: cached " + delta[0] + " staged " + delta[1] + " evicted " + delta[2] + " uploads " + delta[3] + " uncached " + delta[4]);
        if (delta[4] != 0) {
            throw new RuntimeException(delta[4] + " glyphs drawn uncached on the second paint: glyphs over 32 pixels are not cached");
        }
        if (delta[0] < glyphs || delta[1] != 0 || delta[3] != 0) {
            throw new RuntimeException("expected " + glyphs + " cached draws and no staging or upload on the second paint");
        }
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (first.getRGB(x, y) != second.getRGB(x, y)) {
                throw new RuntimeException("the second paint differs from the first at " + x + "," + y);
            }
        }
        int ink = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((second.getRGB(x, y) & 0xffffff) != 0xffffff) ink++;
        if (ink < 5000) {
            throw new RuntimeException("too little ink for three lines of large text: " + ink);
        }
    }

    static void paint(VolatileImage vi) {
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 40));
        g.drawString(TEXT, 8, 44);          // 64-pixel bucket
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 80));
        g.drawString(TEXT, 8, 130);         // 128-pixel bucket
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 10));
        g.drawString(TEXT.substring(0, 8), 8, 200);   // 16-pixel bucket
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 40));
        g.drawString(TEXT.substring(8), 120, 200);    // the same line continues in the 64 bucket
        g.dispose();
    }

    static long[] stats() throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphCacheStats");
        return (long[]) m.invoke(null);
    }
}
