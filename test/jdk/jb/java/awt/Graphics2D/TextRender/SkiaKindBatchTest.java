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
 * @summary Regression test for JBR-10654: glyphs, rects and antialiased shapes interleaved on the
 *          Skia pipeline are composited in the order given.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaKindBatchTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * Cells as Swing paints a list or a tab row (a cell's background, its text, a separator, an
 * icon-like shape): text drawn over a rect, a rect drawn over text and a shape drawn over text
 * are composited in the order given, and the row matches the software loops; the glyphs are
 * drawn in glyph batches (at least one batch draw on the second paint).
 */
public class SkiaKindBatchTest {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 60;
    private static final int CELLS = 30;
    /** LCD text without a destination read differs from the loops by up to about 15 levels at glyph edges (SKTextPipe.cpp). */
    private static final int TOLERANCE = 32;
    private static final int FONT_SIZE = 13;
    /** The cells: one every 29 pixels, 27 wide. */
    private static final int CELL_PITCH = 29;
    private static final int CELL_WIDTH = 27;
    private static final int LEFT_MARGIN = 4;
    private static final int BACKGROUND_TOP = 6;
    private static final int BACKGROUND_HEIGHT = 18;
    private static final int TOKEN_X = 2;
    private static final int TOKEN_BASELINE = 20;
    private static final int SEPARATOR_Y = 28;
    /** The rect over every fourth token. */
    private static final int OVER_RECT_X = 4;
    private static final int OVER_RECT_Y = 10;
    private static final int OVER_RECT_SIZE = 8;
    /** The antialiased oval over every sixth token, or below every other third one. */
    private static final int OVER_OVAL_X = 6;
    private static final int OVER_OVAL_Y = 9;
    private static final int OVER_OVAL_SIZE = 10;
    private static final int BELOW_OVAL_X = 8;
    private static final int BELOW_OVAL_Y = 34;
    private static final int BELOW_OVAL_SIZE = 12;
    private static final Color EVEN_BACKGROUND = new Color(0xE8F0FF);
    private static final Color ODD_BACKGROUND = new Color(0xFFF4E0);
    private static final Color TOKEN_COLOR = new Color(0x0033B3);
    private static final Color SEPARATOR_COLOR = new Color(0x808080);
    private static final Color OVER_RECT_COLOR = new Color(0xC03030);
    private static final Color OVAL_COLOR = new Color(0x10A040);

    record Result(BufferedImage actual, long batchDraws) {
    }

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireSkia();
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);
        Result result = PipelineComparison.keepingContents(graphicsConfig, () -> {
            paint(volatileImage.createGraphics(), font);   // stages every glyph
            volatileImage.getSnapshot();
            long before = PipelineComparison.glyphBatchDraws();
            paint(volatileImage.createGraphics(), font);
            BufferedImage actual = volatileImage.getSnapshot();
            return new Result(actual, PipelineComparison.glyphBatchDraws() - before);
        }, volatileImage);
        BufferedImage reference = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font);

        PipelineComparison.Difference difference = PipelineComparison.compare(reference, result.actual(), TOLERANCE);
        System.out.println(CELLS + " cells of rects, text and shapes: " + result.batchDraws()
                + " glyph batch draws; against the software loops max " + difference.max() + " levels, "
                + difference.beyond() + " pixels beyond " + TOLERANCE);
        if (difference.beyond() > 0) {
            throw new RuntimeException("interleaved rects, glyphs and shapes differ from the software loops: "
                    + difference.beyond() + " pixels beyond " + TOLERANCE + " levels (max " + difference.max() + ")");
        }
        if (result.batchDraws() <= 0) {
            throw new RuntimeException("the row's glyphs were drawn in no glyph batch (" + result.batchDraws() + " batch draws)");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics, Font font) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.setFont(font);
        for (int cell = 0; cell < CELLS; cell++) {
            int x = LEFT_MARGIN + cell * CELL_PITCH;
            graphics.setColor(cell % 2 == 0 ? EVEN_BACKGROUND : ODD_BACKGROUND);
            graphics.fillRect(x, BACKGROUND_TOP, CELL_WIDTH, BACKGROUND_HEIGHT);   // the cell's background: the text goes over it
            graphics.setColor(TOKEN_COLOR);
            graphics.drawString("t" + cell, x + TOKEN_X, TOKEN_BASELINE);
            graphics.setColor(SEPARATOR_COLOR);
            graphics.fillRect(x, SEPARATOR_Y, CELL_WIDTH, 1);                      // a separator below, touching no glyph
            if (cell % 4 == 3) {
                graphics.setColor(OVER_RECT_COLOR);
                graphics.fillRect(x + OVER_RECT_X, OVER_RECT_Y, OVER_RECT_SIZE, OVER_RECT_SIZE);   // over the token: drawn after its glyphs
            }
            if (cell % 3 == 0) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(OVAL_COLOR);
                if (cell % 6 == 0) {
                    graphics.fillOval(x + OVER_OVAL_X, OVER_OVAL_Y, OVER_OVAL_SIZE, OVER_OVAL_SIZE);       // over the token
                } else {
                    graphics.fillOval(x + BELOW_OVAL_X, BELOW_OVAL_Y, BELOW_OVAL_SIZE, BELOW_OVAL_SIZE);   // below it, touching no glyph
                }
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            }
        }
        graphics.dispose();
    }
}
