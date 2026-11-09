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
 * @summary Regression test for JBR-10654: glyphs and rects under changing rect clips on the Skia pipeline
 *          match the software loops.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaClipBatchTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * A row of tokens, each drawn under its own clip as Swing paints cells (some clips cut through
 * glyphs), and filled rects under changing clips (some clips cutting them): the glyphs match
 * the software loops within the tolerance, the rects exactly, so nothing is drawn outside its
 * clip or lost inside it; the glyphs are drawn in glyph batches (at least one batch draw on
 * the second paint).
 */
public class SkiaClipBatchTest {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 80;
    /** The tokens in the rows above this line, the rects below. */
    private static final int TEXT_ROWS = 40;
    private static final int TOKENS = 40;
    /** LCD text without a destination read differs from the loops by up to about 15 levels at glyph edges (SKTextPipe.cpp). */
    private static final int TOLERANCE = 32;
    private static final int FONT_SIZE = 13;
    private static final int LEFT_MARGIN = 4;
    /** No token starts closer to the right edge than this. */
    private static final int RIGHT_MARGIN = 60;
    private static final int TOKEN_CLIP_TOP = 4;
    private static final int TOKEN_CLIP_HEIGHT = 22;
    private static final int TOKEN_BASELINE = 20;
    /** A clip that does not cut a token leaves this much room after it. */
    private static final int CLIP_SLACK = 2;
    private static final int RECTS = 30;
    /** The rects: one every 29 pixels, 24x26 each at y 46, under 20x30 clips at y 44. */
    private static final int RECT_PITCH = 29;
    private static final int RECT_WIDTH = 24;
    private static final int RECT_HEIGHT = 26;
    private static final int RECT_TOP = 46;
    private static final int RECT_CLIP_WIDTH = 20;
    private static final int RECT_CLIP_HEIGHT = 30;
    private static final int RECT_CLIP_TOP = 44;
    /** Every fourth clip starts 5 pixels into its rect, every fifth 6 pixels below its top. */
    private static final int CLIP_INSET_X = 5;
    private static final int CLIP_INSET_Y = 6;
    private static final Color TOKEN_COLOR = new Color(0x0033B3);
    private static final Color RECT_COLOR = new Color(0x871094);

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

        PipelineComparison.Difference text = PipelineComparison.compare(reference, result.actual(),
                new Rectangle(0, 0, WIDTH, TEXT_ROWS), TOLERANCE);
        PipelineComparison.Difference rects = PipelineComparison.compare(reference, result.actual(),
                new Rectangle(0, TEXT_ROWS, WIDTH, HEIGHT - TEXT_ROWS), 0);
        System.out.println(TOKENS + " clipped tokens: " + result.batchDraws()
                + " glyph batch draws; text against the software loops max " + text.max() + " levels, "
                + text.beyond() + " pixels beyond " + TOLERANCE + "; clipped rects " + rects.beyond() + " pixels differ");
        if (text.beyond() > 0 || rects.beyond() > 0) {
            throw new RuntimeException("clipped drawing differs from the software loops: text " + text.beyond()
                    + " pixels beyond " + TOLERANCE + " levels (max " + text.max() + "), rects " + rects.beyond() + " pixels");
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
        graphics.setColor(TOKEN_COLOR);
        FontMetrics fontMetrics = graphics.getFontMetrics();
        int x = LEFT_MARGIN;
        for (int token = 0; token < TOKENS && x < WIDTH - RIGHT_MARGIN; token++) {
            String word = "tok" + token;
            int wordWidth = fontMetrics.stringWidth(word);
            // a cell's clip around the token; every third one cuts the token's last glyph in half
            int clipWidth = token % 3 == 0 ? wordWidth - fontMetrics.charWidth('0') / 2 : wordWidth + CLIP_SLACK;
            graphics.setClip(x, TOKEN_CLIP_TOP, clipWidth, TOKEN_CLIP_HEIGHT);
            graphics.drawString(word, x, TOKEN_BASELINE);
            x += wordWidth + fontMetrics.charWidth(' ');
        }
        // filled rects under changing clips, some cut by their clip
        graphics.setColor(RECT_COLOR);
        for (int rect = 0; rect < RECTS; rect++) {
            int rectX = LEFT_MARGIN + rect * RECT_PITCH;
            graphics.setClip(rectX + (rect % 4 == 0 ? CLIP_INSET_X : 0), RECT_CLIP_TOP + (rect % 5 == 0 ? CLIP_INSET_Y : 0),
                             RECT_CLIP_WIDTH, RECT_CLIP_HEIGHT);
            graphics.fillRect(rectX, RECT_TOP, RECT_WIDTH, RECT_HEIGHT);
        }
        graphics.setClip(null);
        graphics.dispose();
    }
}
