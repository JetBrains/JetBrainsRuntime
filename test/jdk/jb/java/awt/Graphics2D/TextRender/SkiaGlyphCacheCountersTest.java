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
 * @summary Regression test for JBR-10654: a line drawn a second time on the Skia pipeline, at 1x and 2x,
 *          hits the glyph cache for every glyph.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaGlyphCacheCountersTest 1
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaGlyphCacheCountersTest 2
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.VolatileImage;
import java.util.List;

import sun.java2d.skia.SKEnvironment;

/**
 * The glyph cache reports its work (SKEnvironment.glyphCacheCounters: glyphs drawn from the cache,
 * cells staged, cells evicted, atlas row uploads, glyphs drawn uncached). Drawing the same line
 * a second time hits the cache for every glyph: no cell staged, no upload, nothing uncached.
 */
public class SkiaGlyphCacheCountersTest {
    private static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    /** The counters in the order of SKGlyphCache.h's SKGlyphCacheCounter (PipelineComparison.GlyphCacheCounter), for the printout. */
    private static final List<String> COUNTER_NAMES = List.of("cached", "staged", "evicted", "uploads", "uncached");
    /** The line's image at 1x; scaled with the run line's scale. */
    private static final int WIDTH = 600;
    private static final int HEIGHT = 40;
    private static final int FONT_SIZE = 13;
    private static final int LEFT_MARGIN = 4;
    private static final int BASELINE = 26;
    /** Indices into the glyph cache's counters (SKGlyphCache.h, PipelineComparison.GlyphCacheCounter). */
    private static final int CACHED = PipelineComparison.GlyphCacheCounter.GLYPHS_CACHED;
    private static final int STAGED = PipelineComparison.GlyphCacheCounter.CELLS_STAGED;
    private static final int UPLOADS = PipelineComparison.GlyphCacheCounter.ATLAS_UPLOADS;
    private static final int UNCACHED = PipelineComparison.GlyphCacheCounter.GLYPHS_UNCACHED;

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireSkia();
        int scale = Integer.parseInt(args[0]);
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH * scale, HEIGHT * scale,
                                                                                   Transparency.OPAQUE);
        Font font = new Font(Font.DIALOG, Font.PLAIN, FONT_SIZE);
        long[] secondPaint = PipelineComparison.keepingContents(graphicsConfig, () -> {
            paint(volatileImage, font, scale);
            volatileImage.getSnapshot();
            long[] countersAfterFirst = SKEnvironment.glyphCacheCounters();
            paint(volatileImage, font, scale);
            volatileImage.getSnapshot();
            long[] countersAfterSecond = SKEnvironment.glyphCacheCounters();
            return PipelineComparison.countersDelta(countersAfterFirst, countersAfterSecond);
        }, volatileImage);
        StringBuilder report = new StringBuilder("scale " + scale + ", second paint:");
        for (int counter = 0; counter < COUNTER_NAMES.size(); counter++) {
            report.append(' ').append(COUNTER_NAMES.get(counter)).append(' ').append(secondPaint[counter]);
        }
        System.out.println(report);
        int glyphs = TEXT.replace(" ", "").length();
        if (secondPaint[CACHED] < glyphs) {
            throw new RuntimeException(report + ": expected at least " + glyphs + " cached glyph draws");
        }
        if (secondPaint[STAGED] != 0 || secondPaint[UPLOADS] != 0 || secondPaint[UNCACHED] != 0) {
            throw new RuntimeException(report + ": the second paint of the same line staged, uploaded or drew uncached");
        }
        System.out.println("PASSED");
    }

    private static void paint(VolatileImage volatileImage, Font font, int scale) {
        Graphics2D graphics = volatileImage.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, volatileImage.getWidth(), volatileImage.getHeight());
        graphics.scale(scale, scale);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setColor(Color.BLACK);
        graphics.setFont(font);
        graphics.drawString(TEXT, LEFT_MARGIN, BASELINE);
        graphics.dispose();
    }
}
