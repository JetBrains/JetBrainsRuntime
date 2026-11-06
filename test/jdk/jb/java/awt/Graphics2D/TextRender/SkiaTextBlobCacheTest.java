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
 * @summary Skia's text path with the text blob cache (sun.java2d.skia.text.blobCache): a frame of tokens in a
 *          few colours, at whole-pixel and fractional origins, with characters from a fallback font and LCD text,
 *          drawn once without the cache and twice with it, comes out the same, and the second frame with the
 *          cache draws its runs from cached blobs.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.strike=skia SkiaTextBlobCacheTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.strike=skia SkiaTextBlobCacheTest
 */
public class SkiaTextBlobCacheTest {
    static final int W = 900, H = 240;
    static final String[] TOKENS = {"public", "String", "describe(int", "level)", "{", "return", "∑∂Δ → ≠",
            "name", "+", "\"#\"", "level;", "}", "@Override", "final", "var", "xéß"};
    static final Color[] COLOURS = {new Color(0x0033B3), new Color(0x080808), new Color(0x871094), new Color(0x067D17)};

    public static void main(String[] args) throws Exception {
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        if (!(Boolean) env.getMethod("isSkiaEnabled").invoke(null)) {
            System.out.println("Skia is not available: nothing to test");
            return;
        }
        Method setCache = env.getMethod("setTextBlobCache", int.class);
        Method stats = env.getMethod("textBlobCacheStats");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        BufferedImage uncached, cachedFirst, cachedSecond;
        long[] before, after;
        do {
            vi.validate(gc);
            setCache.invoke(null, 0);
            uncached = frame(vi, font);
            setCache.invoke(null, 4096);
            cachedFirst = frame(vi, font);
            before = (long[]) stats.invoke(null);
            cachedSecond = frame(vi, font);
            after = (long[]) stats.invoke(null);
            setCache.invoke(null, 0);
        } while (vi.contentsLost());
        long hits = after[0] - before[0], misses = after[1] - before[1];
        int runs = 0;
        for (int row = 0; row < 6; row++) {
            runs += TOKENS.length;
        }
        int first = differences(uncached, cachedFirst), second = differences(uncached, cachedSecond);
        System.out.println(runs + " runs a frame; the second cached frame: " + hits + " hits, " + misses + " misses; pixels differing"
                + " from the uncached frame: " + first + " (first cached frame), " + second + " (second)");
        if (first != 0 || second != 0) {
            throw new RuntimeException("cached text differs from uncached text: " + first + " / " + second + " pixels");
        }
        if (hits < runs * 9 / 10) {
            throw new RuntimeException("only " + hits + " of " + runs + " runs of the repeated frame drawn from cached blobs");
        }
        System.out.println("PASSED");
    }

    // Six rows of tokens: rows 0-2 at whole-pixel origins, rows 3-5 at fractional ones; greyscale and LCD rows.
    static BufferedImage frame(VolatileImage vi, Font font) {
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        for (int row = 0; row < 6; row++) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, row % 2 == 0
                    ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            float x = 8 + (row >= 3 ? 0.25f * (row - 2) : 0), y = 30 + row * 36 + (row >= 3 ? 0.5f : 0);
            for (int i = 0; i < TOKENS.length; i++) {
                g.setColor(COLOURS[(i + row) % COLOURS.length]);
                g.drawString(TOKENS[i], x, y);
                x += g.getFontMetrics().stringWidth(TOKENS[i]) + 9.5f;
            }
        }
        g.dispose();
        return vi.getSnapshot();
    }

    static int differences(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }
}
