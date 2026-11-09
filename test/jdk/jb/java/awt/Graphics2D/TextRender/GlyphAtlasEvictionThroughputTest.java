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
 * @summary Regression test for JBR-10654: a frame with more distinct glyphs than the Skia glyph atlas
 *          has cells does not cost much more than a frame that fits.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm/timeout=120 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 GlyphAtlasEvictionThroughputTest skia gray
 * @run main/othervm/timeout=120 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 GlyphAtlasEvictionThroughputTest skia lcd
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Arrays;

/**
 * The atlas evicts cells whose last use is already submitted without stalling or flushing per
 * glyph. The frame time of 1,500 distinct glyphs is compared with that of 600, the two kinds of
 * frame alternating so that both see the same machine state; the software loops sit at 2 to 3
 * times, a flush per evicted glyph at hundreds. The limit is wide: the Dawn upload path costs
 * more per row than the software loops, and a paravirtualized GPU measured 8 to 11 times.
 */
public class GlyphAtlasEvictionThroughputTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1000;
    /** Distinct glyphs of a frame that fits the 1,024 cells of the atlas (SKEnvironment.GLYPH_CACHE_TEXELS, 32x32 cells). */
    private static final int FITTING = 600;
    /** Distinct glyphs of a frame that evicts cells. */
    private static final int EVICTING = 1500;
    /** The evicting frame may cost at most this many times the fitting one (measured 2 to 3 on the loops, 8 to 11 paravirtualized). */
    private static final double MAX_RATIO = 16.0;
    private static final int WARMUP = 20;
    private static final int FRAMES = 60;
    /** The printable ASCII range, '!' to '~': 94 glyphs per font, drawn as two lines of 47. */
    private static final char FIRST_PRINTABLE = 33;
    private static final char LAST_PRINTABLE = 126;
    private static final int GLYPHS_PER_FONT = LAST_PRINTABLE - FIRST_PRINTABLE + 1;
    private static final int GLYPHS_PER_LINE = GLYPHS_PER_FONT / 2;
    /** The smallest font size; each further font is one point larger. */
    private static final int SMALLEST_FONT_SIZE = 9;
    private static final int TOP_MARGIN = 30;
    private static final int LEFT_MARGIN = 10;
    private static final int LINE_GAP = 2;
    private static final int FONT_GAP = 6;
    private static final int BOTTOM_MARGIN = 20;
    /** The pixel read back to complete a frame: inside the first line of text. */
    private static final int SYNC_PIXEL_X = 5;
    private static final int SYNC_PIXEL_Y = 5;
    private static final String ALPHABET = printableAlphabet();

    private static String printableAlphabet() {
        StringBuilder alphabet = new StringBuilder();
        for (char character = FIRST_PRINTABLE; character <= LAST_PRINTABLE; character++) {
            alphabet.append(character);
        }
        return alphabet.toString();
    }

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        boolean lcdText = args[1].equals("lcd");
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        image.validate(graphicsConfig);
        Font[] fittingFonts = fonts(FITTING);
        Font[] evictingFonts = fonts(EVICTING);
        BufferedImage sync = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        long[] fittingTimes = new long[FRAMES];
        long[] evictingTimes = new long[FRAMES];
        // one fitting frame, one evicting frame, and again: both kinds see the same machine state
        for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
            long fittingNanos = frameNanos(image, sync, FITTING, fittingFonts, lcdText);
            long evictingNanos = frameNanos(image, sync, EVICTING, evictingFonts, lcdText);
            if (frame >= WARMUP) {
                fittingTimes[frame - WARMUP] = fittingNanos;
                evictingTimes[frame - WARMUP] = evictingNanos;
            }
        }
        double fittingMillis = medianMillis(fittingTimes);
        double evictingMillis = medianMillis(evictingTimes);
        double ratio = evictingMillis / fittingMillis;
        System.out.printf("%s: %d glyphs %.2f ms, %d glyphs %.2f ms, ratio %.1f%n",
                          lcdText ? "LCD" : "grayscale", FITTING, fittingMillis, EVICTING, evictingMillis, ratio);
        if (ratio > MAX_RATIO) {
            throw new RuntimeException("A frame that evicts atlas cells costs " + String.format("%.1f", ratio)
                    + " times a frame that fits (limit " + MAX_RATIO + ")");
        }
        System.out.println("PASSED");
    }

    /** One font per {@link #GLYPHS_PER_FONT} distinct glyphs, alternating plain and bold. */
    private static Font[] fonts(int distinctGlyphs) {
        int sizes = (distinctGlyphs + GLYPHS_PER_FONT - 1) / GLYPHS_PER_FONT;
        Font[] fonts = new Font[sizes];
        for (int sizeIndex = 0; sizeIndex < sizes; sizeIndex++) {
            fonts[sizeIndex] = new Font(Font.SANS_SERIF, sizeIndex % 2 == 0 ? Font.PLAIN : Font.BOLD,
                                        SMALLEST_FONT_SIZE + sizeIndex);
        }
        return fonts;
    }

    /** Draws a frame of the given number of distinct glyphs and completes it with a one-pixel readback. */
    private static long frameNanos(VolatileImage image, BufferedImage sync, int distinctGlyphs, Font[] fonts,
                                   boolean lcdText) {
        long start = System.nanoTime();
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                      lcdText ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                              : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.BLACK);
            int y = TOP_MARGIN;
            int drawn = 0;
            for (int fontIndex = 0; fontIndex < fonts.length && drawn < distinctGlyphs; fontIndex++) {
                Font font = fonts[fontIndex];
                graphics.setFont(font);
                int glyphsInFont = Math.min(GLYPHS_PER_FONT, distinctGlyphs - drawn);
                graphics.drawString(ALPHABET.substring(0, Math.min(GLYPHS_PER_LINE, glyphsInFont)), LEFT_MARGIN, y);
                if (glyphsInFont > GLYPHS_PER_LINE) {
                    graphics.drawString(ALPHABET.substring(GLYPHS_PER_LINE, glyphsInFont), LEFT_MARGIN,
                                        y + font.getSize() + LINE_GAP);
                }
                drawn += glyphsInFont;
                y += 2 * font.getSize() + FONT_GAP;
                if (y > HEIGHT - BOTTOM_MARGIN) {
                    y = TOP_MARGIN;
                }
            }
        } finally {
            graphics.dispose();
        }
        Graphics2D syncGraphics = sync.createGraphics();
        try {
            syncGraphics.drawImage(image, 0, 0, 1, 1, SYNC_PIXEL_X, SYNC_PIXEL_Y, SYNC_PIXEL_X + 1, SYNC_PIXEL_Y + 1, null);
        } finally {
            syncGraphics.dispose();
        }
        sync.getRGB(0, 0);
        return System.nanoTime() - start;
    }

    private static double medianMillis(long[] times) {
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1e6;
    }
}
