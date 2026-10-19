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

/*
 * @test
 * @key headful
 * @summary LCD text on a GPU pipeline against the software loops, on
 *          light, dark and mid-tone backgrounds in several text colours,
 *          with a second and a third string drawn over the first so that
 *          glyphs of two and three lists overlap inside one batch. The
 *          blender mode reads the destination and reproduces the loops'
 *          per-channel gamma blend closely; the two-pass mode folds the
 *          gamma correction into the coverage and is allowed a few levels
 *          at glyph edges. Overlapping glyphs must not change the result
 *          beyond that: each glyph has to be composited on top of the
 *          previous one, not added to it. Every sheet is drawn at the
 *          default LCD contrast and at 250, where the GPU pipelines' gamma
 *          blend, Metal's included, sits up to 52 levels off the loops'
 *          table-driven one; the second tolerance on the run line is for
 *          that sheet.
 *          Metal's blend is up to 43 levels off the loops in single edge
 *          pixels and runs with a wider tolerance.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @library ..
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest skia 16 64
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest skia 32 64
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest skia 32 64
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest metal 48 64
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class LcdTextBackgroundsTest {

    private static final int W = 260;
    private static final int H = 40;
    private static final int[] BACKGROUNDS = { 0xFFFFFF, 0x000000, 0x2B2B2B, 0x404040, 0x808080, 0xF2F2F2 };
    private static final int[] COLORS = { 0x000000, 0xFFFFFF, 0xBBBBBB, 0x3030C0, 0xFF8800 };

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int defaultTolerance = Integer.parseInt(args[1]);
        int highContrastTolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (int contrast : new int[] { 0, 250 }) {
            int tolerance = contrast > 0 ? highContrastTolerance : defaultTolerance;
            for (int background : BACKGROUNDS) {
                for (int color : COLORS) {
                    for (int copies = 1; copies <= 3; copies++) {
                        boolean overlap = copies > 1;
                        int count = copies;
                        BufferedImage reference = PipelineComparison.softwareReference(W, H,
                                g -> paint(g, background, color, count, contrast));
                        BufferedImage actual = PipelineComparison.rendered(gc, W, H,
                                g -> paint(g, background, color, count, contrast));
                        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
                        String what = String.format("%06x on %06x%s", color, background,
                                overlap ? " with " + (copies - 1) + " overlapping" : "")
                                + (contrast > 0 ? " contrast " + contrast : "");
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

    private static void paint(Graphics2D g, int background, int color, int copies, int contrast) {
        if (contrast > 0) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, contrast);
        }
        g.setColor(new Color(background));
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(new Color(color));
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        g.setFont(font);
        g.drawString("Hamburgefonstiv ffi fl 0123 Wq", 4, 15);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("if (x <= y) { return ffi; }", 4, 32);
        // further glyph lists of the same colour, each one pixel over the
        // previous: glyphs that overlap pending ones go to later layers of the
        // batch, and a third copy overlaps both
        g.setFont(font);
        for (int copy = 1; copy < copies; copy++) {
            g.drawString("Hamburgefonstiv ffi fl 0123 Wq", 4 + copy, 15);
        }
    }
}
