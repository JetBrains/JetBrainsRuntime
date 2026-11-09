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
 * @summary Regression test for JBR-10654: image blits between glyphs in cell clips on the Skia pipeline
 *          match the software loops.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaBlitBatchTest
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * A row of tokens, each in its own cell clip with an icon beside it (an accelerated image and
 * an uploaded one in turn), as Swing paints a tree or a tab row. Icons drawn over their token
 * cover it, icons cut by their clip are cut, and the whole row matches the software loops; the
 * glyphs are drawn in glyph batches (at least one batch draw on the second paint).
 */
public class SkiaBlitBatchTest {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 60;
    private static final int TOKENS = 30;
    private static final int ICON_SIZE = 12;
    /** LCD text without a destination read differs from the loops by up to about 15 levels at glyph edges (SKTextPipe.cpp). */
    private static final int TOLERANCE = 32;
    private static final int FONT_SIZE = 13;
    /** The cells: one every 29 pixels, each clipped to 27 wide. */
    private static final int CELL_PITCH = 29;
    private static final int CELL_WIDTH = 27;
    private static final int LEFT_MARGIN = 4;
    private static final int CELL_TOP = 4;
    /** Every third cell's clip ends above the icon below the token and cuts it. */
    private static final int CUTTING_CLIP_HEIGHT = 34;
    private static final int FULL_CLIP_HEIGHT = 50;
    private static final int TOKEN_BASELINE = 20;
    /** Where the icon goes over the token and where below it. */
    private static final int ICON_OVER_X = 2;
    private static final int ICON_OVER_Y = 8;
    private static final int ICON_BELOW_X = 4;
    private static final int ICON_BELOW_Y = 28;
    private static final Color TOKEN_COLOR = new Color(0x0033B3);
    /** The icon's two colors, far from the text's and the background's; its bottom row at half alpha. */
    private static final int ICON_COLOR_A = 0xffe07000;
    private static final int ICON_COLOR_B = 0xff10b040;
    private static final int HALF_ALPHA = 0x80000000;

    record Result(BufferedImage actual, long batchDraws) {
    }

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireSkia();
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        BufferedImage pixels = icon();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        VolatileImage accelerated = graphicsConfig.createCompatibleVolatileImage(ICON_SIZE, ICON_SIZE, Transparency.TRANSLUCENT);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);
        Result result = PipelineComparison.keepingContents(graphicsConfig, () -> {
            Graphics2D iconGraphics = accelerated.createGraphics();
            iconGraphics.setComposite(AlphaComposite.Src);
            iconGraphics.drawImage(pixels, 0, 0, null);
            iconGraphics.dispose();
            paint(volatileImage.createGraphics(), font, accelerated, pixels);   // stages every glyph
            volatileImage.getSnapshot();
            long before = PipelineComparison.glyphBatchDraws();
            paint(volatileImage.createGraphics(), font, accelerated, pixels);
            BufferedImage actual = volatileImage.getSnapshot();
            return new Result(actual, PipelineComparison.glyphBatchDraws() - before);
        }, volatileImage, accelerated);
        BufferedImage reference = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font, pixels, pixels);

        PipelineComparison.Difference difference = PipelineComparison.compare(reference, result.actual(), TOLERANCE);
        System.out.println(TOKENS + " tokens with an icon each: " + result.batchDraws()
                + " glyph batch draws; against the software loops max " + difference.max() + " levels, "
                + difference.beyond() + " pixels beyond " + TOLERANCE);
        if (difference.beyond() > 0) {
            throw new RuntimeException("icons between glyphs differ from the software loops: " + difference.beyond()
                    + " pixels beyond " + TOLERANCE + " levels (max " + difference.max() + ")");
        }
        if (result.batchDraws() <= 0) {
            throw new RuntimeException("the row's glyphs were drawn in no glyph batch (" + result.batchDraws() + " batch draws)");
        }
        System.out.println("PASSED");
    }

    /** Opaque but for a translucent bottom row, in colors far from the text's and the background's. */
    private static BufferedImage icon() {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                int argb = (x + y) % 4 < 2 ? ICON_COLOR_A : ICON_COLOR_B;
                image.setRGB(x, y, y == ICON_SIZE - 1 ? (argb & 0x00ffffff) | HALF_ALPHA : argb);
            }
        }
        return image;
    }

    private static void paint(Graphics2D graphics, Font font, Image accelerated, Image pixels) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.setFont(font);
        graphics.setColor(TOKEN_COLOR);
        for (int token = 0; token < TOKENS; token++) {
            int x = LEFT_MARGIN + token * CELL_PITCH;
            // a cell's clip; every third one cuts the icon below the token
            graphics.setClip(x, CELL_TOP, CELL_WIDTH, token % 3 == 0 ? CUTTING_CLIP_HEIGHT : FULL_CLIP_HEIGHT);
            graphics.drawString("t" + token, x, TOKEN_BASELINE);
            Image icon = token % 2 == 0 ? accelerated : pixels;
            if (token % 5 == 4) {
                graphics.drawImage(icon, x + ICON_OVER_X, ICON_OVER_Y, null);     // over the token: drawn after its glyphs
            } else {
                graphics.drawImage(icon, x + ICON_BELOW_X, ICON_BELOW_Y, null);   // below it, touching no glyph
            }
        }
        graphics.setClip(null);
        graphics.dispose();
    }
}
