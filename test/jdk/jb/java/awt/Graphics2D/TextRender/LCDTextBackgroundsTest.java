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
 * @summary Regression test for JBR-10654: LCD text on the Skia pipeline matches the software loops
 *          on light, dark and mid-tone backgrounds, also where glyphs of two and three lists overlap.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=blend -Dsun.java2d.uiScale=1 LCDTextBackgroundsTest skia 16 64
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=twoPass -Dsun.java2d.uiScale=1 LCDTextBackgroundsTest skia 32 64
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=accumulate -Dsun.java2d.uiScale=1 LCDTextBackgroundsTest skia 32 64
 */

/*
 * @test id=vulkan
 * @key headful
 * @summary Regression test for JBR-10654: the two-pass LCD compositing on Graphite's native Vulkan
 *          backend, where a kPlus second pass was blended in the shader from an input attachment.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.skia.text.lcdCompositing=twoPass -Dsun.java2d.uiScale=1 LCDTextBackgroundsTest skia 32 64
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Light, dark and mid-tone backgrounds in several text colors, with a second and a third string
 * drawn over the first so that glyphs of two and three lists overlap inside one batch. The blend
 * compositing (sun.java2d.skia.text.lcdCompositing=blend) reads the destination and reproduces
 * the loops' per-channel gamma blend closely; the two-pass compositing folds the gamma correction
 * into the coverage and is allowed a few levels at glyph edges (the first tolerance on the run
 * line). Overlapping glyphs must not change the result beyond that: each glyph has to be
 * composited on top of the previous one, not added to it. Every sheet is drawn at the default
 * LCD contrast and at 250, where a GPU pipeline's gamma blend sits up to 52 levels off the loops'
 * table-driven one; the second tolerance is for that sheet.
 */
public class LCDTextBackgroundsTest {

    private static final int WIDTH = 260;
    private static final int HEIGHT = 40;
    private static final int[] BACKGROUNDS = { 0xFFFFFF, 0x000000, 0x2B2B2B, 0x404040, 0x808080, 0xF2F2F2 };
    private static final int[] COLORS = { 0x000000, 0xFFFFFF, 0xBBBBBB, 0x3030C0, 0xFF8800 };
    /** KEY_TEXT_LCD_CONTRAST left at its default (0 here: the hint is not set), and the high end of its 100..250 range. */
    private static final int DEFAULT_CONTRAST = 0;
    private static final int HIGH_CONTRAST = 250;
    /** The first string alone, then with one and two copies over it. */
    private static final int MAX_COPIES = 3;
    private static final String SAMPLE = "Hamburgefonstiv ffi fl 0123 Wq";
    private static final String CODE_SAMPLE = "if (x <= y) { return ffi; }";
    private static final int LEFT_MARGIN = 4;
    private static final int FIRST_BASELINE = 15;
    private static final int SECOND_BASELINE = 32;
    private static final int SAMPLE_FONT_SIZE = 13;
    private static final int CODE_FONT_SIZE = 12;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int defaultTolerance = Integer.parseInt(args[1]);
        int highContrastTolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        StringBuilder failures = new StringBuilder();
        for (int contrast : new int[] { DEFAULT_CONTRAST, HIGH_CONTRAST }) {
            int tolerance = contrast > DEFAULT_CONTRAST ? highContrastTolerance : defaultTolerance;
            for (int background : BACKGROUNDS) {
                for (int color : COLORS) {
                    for (int copies = 1; copies <= MAX_COPIES; copies++) {
                        int copyCount = copies;
                        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT,
                                graphics -> paint(graphics, background, color, copyCount, contrast));
                        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT,
                                graphics -> paint(graphics, background, color, copyCount, contrast));
                        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
                        String what = String.format("%06x on %06x%s", color, background,
                                copies > 1 ? " with " + (copies - 1) + " overlapping" : "")
                                + (contrast > DEFAULT_CONTRAST ? " contrast " + contrast : "");
                        System.out.println(what + ": max " + difference.max() + " levels, "
                                + difference.beyond() + " pixels beyond " + tolerance);
                        if (difference.beyond() > 0) {
                            failures.append(what).append(" (max ").append(difference.max()).append(") ");
                        }
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("LCD text differs from the software loops beyond the tolerances "
                    + defaultTolerance + " and " + highContrastTolerance + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics, int background, int color, int copies, int contrast) {
        if (contrast > DEFAULT_CONTRAST) {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
        }
        graphics.setColor(new Color(background));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.setColor(new Color(color));
        Font sampleFont = new Font(Font.DIALOG, Font.PLAIN, SAMPLE_FONT_SIZE);
        graphics.setFont(sampleFont);
        graphics.drawString(SAMPLE, LEFT_MARGIN, FIRST_BASELINE);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, CODE_FONT_SIZE));
        graphics.drawString(CODE_SAMPLE, LEFT_MARGIN, SECOND_BASELINE);
        // further glyph lists of the same color, each one pixel over the
        // previous: glyphs that overlap pending ones go to later layers of the
        // batch, and a third copy overlaps both
        graphics.setFont(sampleFont);
        for (int copy = 1; copy < copies; copy++) {
            graphics.drawString(SAMPLE, LEFT_MARGIN + copy, FIRST_BASELINE);
        }
    }
}
