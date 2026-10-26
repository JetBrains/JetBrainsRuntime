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
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary The Skia pipe's glyph cache reports its work (SKEnvironment.glyphCacheStats:
 *          glyphs drawn from the cache, cells staged, cells evicted, atlas row uploads,
 *          glyphs drawn uncached). Drawing the same line a second time, at 1x and at 2x,
 *          hits the cache for every glyph: no cell staged, no upload, nothing uncached.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaGlyphCacheStatsTest 1
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaGlyphCacheStatsTest 2
 */
public class SkiaGlyphCacheStatsTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final String[] NAMES = {"cached", "staged", "evicted", "uploads", "uncached"};

    public static void main(String[] args) throws Exception {
        final int scale = Integer.parseInt(args[0]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        VolatileImage vi = gc.createCompatibleVolatileImage(600 * scale, 40 * scale, Transparency.OPAQUE);
        Font font = new Font("Helvetica Neue", Font.PLAIN, 13);
        long[] first = null, second = null;
        do {
            vi.validate(gc);
            paint(vi, font, scale);
            vi.getSnapshot();
            long[] a = stats();
            paint(vi, font, scale);
            vi.getSnapshot();
            long[] b = stats();
            first = a;
            second = new long[a.length];
            for (int i = 0; i < a.length; i++) second[i] = b[i] - a[i];
        } while (vi.contentsLost());
        StringBuilder sb = new StringBuilder("scale " + scale + ", second paint:");
        for (int i = 0; i < NAMES.length; i++) sb.append(' ').append(NAMES[i]).append(' ').append(second[i]);
        System.out.println(sb);
        final int glyphs = TEXT.replace(" ", "").length();
        if (second[0] < glyphs) {
            throw new RuntimeException(sb + ": expected at least " + glyphs + " cached glyph draws");
        }
        if (second[1] != 0 || second[3] != 0 || second[4] != 0) {
            throw new RuntimeException(sb + ": the second paint of the same line staged, uploaded or drew uncached");
        }
    }

    static void paint(VolatileImage vi, Font font, int scale) {
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, vi.getWidth(), vi.getHeight());
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
    }

    static long[] stats() throws Exception {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphCacheStats");
            return (long[]) m.invoke(null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("the backend has no glyph cache statistics", e);
        }
    }
}
