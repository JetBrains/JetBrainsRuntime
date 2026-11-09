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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: the Skia pipeline's glyph caches hold glyphs up to 128 pixels
 *          in size buckets.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaGlyphCacheBucketsTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.java2d.skia.SKEnvironment;

/**
 * The glyph caches come in size buckets (cells of 16, 32, 64 and 128 pixels, SKGlyphCache), so
 * glyph images up to 128 pixels are cached: text at 40 pt (40-pixel glyphs, the 64 bucket), at
 * 80 pt (the 128 bucket) and a line mixing 10 pt and 40 pt all hit the caches on their second
 * paint, with no glyph drawn uncached and the result identical to the first paint. Before the
 * buckets any glyph over 32 pixels was uploaded and drawn on its own every frame, which on a 2x
 * display is every glyph above 16 pt.
 */
public class SkiaGlyphCacheBucketsTest {
    private static final String TEXT = "Hamburgefonts Wg";
    private static final int WIDTH = 900;
    private static final int HEIGHT = 240;
    /** Font sizes whose glyph images land in the 64-, 128- and 16-pixel buckets. */
    private static final int LARGE_FONT_SIZE = 40;
    private static final int HUGE_FONT_SIZE = 80;
    private static final int SMALL_FONT_SIZE = 10;
    /** The three lines: 40 pt, 80 pt, and 10 pt continued in 40 pt. */
    private static final int LEFT_MARGIN = 8;
    private static final int LARGE_BASELINE = 44;
    private static final int HUGE_BASELINE = 130;
    private static final int MIXED_BASELINE = 200;
    private static final int MIXED_LINE_SPLIT = 8;
    private static final int MIXED_LINE_CONTINUATION_X = 120;
    /** The text's glyphs are drawn three times over (the mixed line draws each once). */
    private static final int LINES = 3;
    /** Three lines of 40 to 80 pt text ink far more pixels than this; fewer means text went missing. */
    private static final int MIN_INK_PIXELS = 5000;
    private static final int WHITE = 0xffffff;
    /** Indices into the glyph cache's counters (SKGlyphCache.h, PipelineComparison.GlyphCacheCounter). */
    private static final int CACHED = PipelineComparison.GlyphCacheCounter.GLYPHS_CACHED;
    private static final int STAGED = PipelineComparison.GlyphCacheCounter.CELLS_STAGED;
    private static final int EVICTED = PipelineComparison.GlyphCacheCounter.CELLS_EVICTED;
    private static final int UPLOADS = PipelineComparison.GlyphCacheCounter.ATLAS_UPLOADS;
    private static final int UNCACHED = PipelineComparison.GlyphCacheCounter.GLYPHS_UNCACHED;
    private static final int BATCH_DRAWS = PipelineComparison.GlyphCacheCounter.BATCH_DRAWS;

    /**
     * The 16- and 32-pixel buckets hold 1024 cells, the 64- and 128-pixel buckets 256 (SKGraphicsConfig.cpp,
     * glyphCacheSide): more distinct small glyphs than a large bucket holds all stay cached only in a
     * small bucket. Printable ASCII, Latin-1, Greek and Cyrillic letters: well over 256 distinct glyphs
     * (the two large buckets cannot be told apart this way: they hold the same number of cells).
     */
    private static final int LARGE_BUCKET_CELLS = 256;
    private static final String MANY_SMALL_GLYPHS = codePoints(0x21, 0x7e) + codePoints(0xa1, 0xff)
                                                     + codePoints(0x391, 0x3c9) + codePoints(0x410, 0x44f);
    private static final int SMALL_GLYPHS_PER_LINE = 60;
    private static final int SMALL_LINE_PITCH = 14;

    record Result(BufferedImage first, BufferedImage second, long[] delta) {
    }

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireSkia();
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        Result result = PipelineComparison.keepingContents(graphicsConfig, () -> {
            paint(volatileImage);
            BufferedImage first = volatileImage.getSnapshot();
            long[] countersAfterFirst = SKEnvironment.glyphCacheCounters();
            paint(volatileImage);
            BufferedImage second = volatileImage.getSnapshot();
            long[] countersAfterSecond = SKEnvironment.glyphCacheCounters();
            return new Result(first, second, PipelineComparison.countersDelta(countersAfterFirst, countersAfterSecond));
        }, volatileImage);
        long[] delta = result.delta();
        int glyphs = LINES * TEXT.replace(" ", "").length();
        System.out.println("second paint: cached " + delta[CACHED] + " staged " + delta[STAGED] + " evicted " + delta[EVICTED]
                + " uploads " + delta[UPLOADS] + " uncached " + delta[UNCACHED] + " batch draws " + delta[BATCH_DRAWS]);
        if (delta[UNCACHED] != 0) {
            throw new RuntimeException(delta[UNCACHED]
                    + " glyphs drawn uncached on the second paint: glyphs over 32 pixels are not cached");
        }
        if (delta[CACHED] < glyphs || delta[STAGED] != 0 || delta[UPLOADS] != 0) {
            throw new RuntimeException("expected " + glyphs + " cached draws and no staging or upload on the second paint");
        }
        BufferedImage first = result.first();
        BufferedImage second = result.second();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    throw new RuntimeException("the second paint differs from the first at " + x + "," + y);
                }
            }
        }
        int inkPixels = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if ((second.getRGB(x, y) & WHITE) != WHITE) {
                    inkPixels++;
                }
            }
        }
        if (inkPixels < MIN_INK_PIXELS) {
            throw new RuntimeException("too little ink for three lines of large text: " + inkPixels);
        }
        checkSmallGlyphsStayCached(graphicsConfig);
        System.out.println("PASSED");
    }

    /** More distinct 10 pt glyphs than a large bucket holds, painted twice: the second paint stages and evicts nothing. */
    private static void checkSmallGlyphsStayCached(GraphicsConfiguration graphicsConfig) throws Exception {
        if (MANY_SMALL_GLYPHS.length() <= LARGE_BUCKET_CELLS) {
            throw new IllegalStateException("the alphabet must exceed a large bucket's " + LARGE_BUCKET_CELLS + " cells");
        }
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        long[] delta = PipelineComparison.keepingContents(graphicsConfig, () -> {
            paintSmallGlyphs(volatileImage);
            volatileImage.getSnapshot();   // the queue flushed: the counters are up to date
            long[] countersAfterFirst = SKEnvironment.glyphCacheCounters();
            paintSmallGlyphs(volatileImage);
            volatileImage.getSnapshot();
            return PipelineComparison.countersDelta(countersAfterFirst, SKEnvironment.glyphCacheCounters());
        }, volatileImage);
        System.out.println(MANY_SMALL_GLYPHS.length() + " small glyphs, second paint: cached " + delta[CACHED] + " staged "
                + delta[STAGED] + " evicted " + delta[EVICTED] + " uncached " + delta[UNCACHED]);
        // a few characters share a glyph image; what matters is more cached draws than a large bucket has cells
        if (delta[CACHED] <= LARGE_BUCKET_CELLS) {
            throw new RuntimeException("expected more than " + LARGE_BUCKET_CELLS + " cached glyph draws on the second paint, got "
                    + delta[CACHED]);
        }
        if (delta[STAGED] != 0 || delta[EVICTED] != 0 || delta[UNCACHED] != 0) {
            throw new RuntimeException(MANY_SMALL_GLYPHS.length() + " distinct 10 pt glyphs do not all stay cached: the small"
                    + " glyphs landed in a bucket of " + LARGE_BUCKET_CELLS + " cells");
        }
    }

    /** The alphabet in 10 pt lines, at integer positions (one sub-pixel variant per glyph). */
    private static void paintSmallGlyphs(VolatileImage volatileImage) {
        Graphics2D graphics = volatileImage.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, SMALL_FONT_SIZE));
        for (int line = 0; line * SMALL_GLYPHS_PER_LINE < MANY_SMALL_GLYPHS.length(); line++) {
            int from = line * SMALL_GLYPHS_PER_LINE;
            graphics.drawString(MANY_SMALL_GLYPHS.substring(from, Math.min(from + SMALL_GLYPHS_PER_LINE, MANY_SMALL_GLYPHS.length())),
                                LEFT_MARGIN, SMALL_LINE_PITCH * (line + 1));
        }
        graphics.dispose();
    }

    private static String codePoints(int first, int last) {
        StringBuilder alphabet = new StringBuilder();
        for (int codePoint = first; codePoint <= last; codePoint++) {
            alphabet.appendCodePoint(codePoint);
        }
        return alphabet.toString();
    }

    private static void paint(VolatileImage volatileImage) {
        Graphics2D graphics = volatileImage.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, LARGE_FONT_SIZE));
        graphics.drawString(TEXT, LEFT_MARGIN, LARGE_BASELINE);                                  // 64-pixel bucket
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, HUGE_FONT_SIZE));
        graphics.drawString(TEXT, LEFT_MARGIN, HUGE_BASELINE);                                   // 128-pixel bucket
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, SMALL_FONT_SIZE));
        graphics.drawString(TEXT.substring(0, MIXED_LINE_SPLIT), LEFT_MARGIN, MIXED_BASELINE);   // 16-pixel bucket
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, LARGE_FONT_SIZE));
        graphics.drawString(TEXT.substring(MIXED_LINE_SPLIT), MIXED_LINE_CONTINUATION_X, MIXED_BASELINE);   // the same line continues in the 64 bucket
        graphics.dispose();
    }
}
