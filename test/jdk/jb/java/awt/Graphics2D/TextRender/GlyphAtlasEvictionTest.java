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
 *          has cells draws every glyph as the software loops do, in grayscale and in LCD mode.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia gray 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia lcd 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=accumulate -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia lcd 32
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
 * Such a frame evicts cells in the middle of glyph lists. The flush that precedes an eviction must
 * not lose the rest of the list: every glyph of the frame has to be drawn, and drawn like the
 * software loops draw it. The third run line uses the accumulate compositing mode
 * (sun.java2d.skia.text.lcdCompositing), which composites a batch's coverage from a hook.
 */
public class GlyphAtlasEvictionTest {

    private static final int WIDTH = 1400;
    private static final int HEIGHT = 1000;
    /** The printable ASCII range, '!' to '~': 94 glyphs per font. */
    private static final char FIRST_PRINTABLE = 33;
    private static final char LAST_PRINTABLE = 126;
    /** The alphabet is drawn as two overlapping lines, so every glyph image of a font is used. */
    private static final int FIRST_LINE_GLYPHS = 60;
    private static final int SECOND_LINE_FROM = 34;
    /**
     * 94 glyphs in the 22 sizes that fit the image height, in two weights: about twice the
     * 1,024 cells of the 1024x1024 atlas (SKEnvironment.GLYPH_CACHE_TEXELS) of 32x32 cells.
     */
    private static final int SIZES = 32;
    /** The smallest font size; each further font is one point larger. */
    private static final int SMALLEST_FONT_SIZE = 8;
    /** The later frames draw with a full atlas. */
    private static final int FRAMES = 3;
    private static final int TOP_MARGIN = 20;
    private static final int LEFT_MARGIN = 10;
    private static final int LINE_GAP = 2;
    private static final int FONT_GAP = 8;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        boolean lcdText = args[1].equals("lcd");
        int tolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, graphics -> paint(graphics, lcdText));
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        image.validate(graphicsConfig);
        BufferedImage actual = null;
        for (int frame = 0; frame < FRAMES; frame++) {
            actual = PipelineComparison.rendered(image, graphics -> paint(graphics, lcdText));
        }
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
        String mode = lcdText ? "LCD" : "grayscale";
        System.out.println(mode + ": " + difference.missingInk() + " pixels of ink missing, "
                + difference.beyond() + " pixels more than " + tolerance + " levels off, max " + difference.max());
        if (difference.missingInk() > 0 || difference.beyond() > 0) {
            throw new RuntimeException(mode + " text with glyph atlas evictions differs from the software loops: "
                    + difference.missingInk() + " pixels of ink missing, " + difference.beyond()
                    + " pixels more than " + tolerance + " levels off");
        }
        System.out.println("PASSED");
    }

    /** The printable ASCII characters in order. */
    private static String printableAlphabet() {
        StringBuilder alphabet = new StringBuilder();
        for (char character = FIRST_PRINTABLE; character <= LAST_PRINTABLE; character++) {
            alphabet.append(character);
        }
        return alphabet.toString();
    }

    /** Draws the alphabet in every size, so that the frame needs more cells than the atlas has. */
    private static void paint(Graphics2D graphics, boolean lcdText) {
        String alphabet = printableAlphabet();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                  lcdText ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                          : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.BLACK);
        int y = TOP_MARGIN;
        for (int sizeIndex = 0; sizeIndex < SIZES; sizeIndex++) {
            Font font = new Font(Font.SANS_SERIF, sizeIndex % 2 == 0 ? Font.PLAIN : Font.BOLD, SMALLEST_FONT_SIZE + sizeIndex);
            graphics.setFont(font);
            graphics.drawString(alphabet.substring(0, FIRST_LINE_GLYPHS), LEFT_MARGIN, y);
            graphics.drawString(alphabet.substring(SECOND_LINE_FROM), LEFT_MARGIN, y + font.getSize() + LINE_GAP);
            y += 2 * font.getSize() + FONT_GAP;
            if (y > HEIGHT - TOP_MARGIN) {
                break;
            }
        }
    }
}
