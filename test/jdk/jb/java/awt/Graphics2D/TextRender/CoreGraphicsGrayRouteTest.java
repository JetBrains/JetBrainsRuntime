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
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary The Skia font scaler's CoreGraphics grayscale route (sun.java2d.skia.text.gray=cg)
 *          rasterizes glyphs through CoreGraphics with font smoothing on, for the
 *          luminance of the text: the platform darkens light text more than dark
 *          text (measured: +40 % ink for white, +15 % for black, against plain
 *          coverage). The test draws text in the route and compares its ink with
 *          the plain coverage of the same glyph outlines filled with
 *          antialiasing: white text must carry at least a quarter more ink,
 *          black text between 3 and 25 % more.
 *          Without the luminance property the route takes the luminance from the
 *          text colour itself: black and white text drawn alternately in one
 *          process, with drawString and with a GlyphVector, each get their own
 *          glyph images.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.cgLuminance=255 CoreGraphicsGrayRouteTest light
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.cgLuminance=0 CoreGraphicsGrayRouteTest dark
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg CoreGraphicsGrayRouteTest colours
 */
public class CoreGraphicsGrayRouteTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 400, H = 40;

    public static void main(String[] args) {
        Font font = new Font("Helvetica Neue", Font.PLAIN, 13);
        if (args[0].equals("colours")) {
            // alternate, so a shared image for both colours would show
            for (int i = 0; i < 2; i++) {
                for (boolean glyphVector : new boolean[] {false, true}) {
                    check(font, true, glyphVector);
                    check(font, false, glyphVector);
                }
            }
            return;
        }
        check(font, args[0].equals("light"), false);
    }

    static void check(Font font, boolean light, boolean glyphVector) {
        // the route under test: text drawn in the luminance the property names, on the far paper
        Color ink = light ? Color.WHITE : Color.BLACK, paper = light ? Color.BLACK : Color.WHITE;
        BufferedImage text = render(font, ink, paper, glyphVector ? Mode.GLYPH_VECTOR : Mode.STRING);
        // plain coverage: the same outlines filled with antialiasing by Java's own rasterizer
        BufferedImage outline = render(font, ink, paper, Mode.OUTLINE);
        long textInk = ink(text, paper), outlineInk = ink(outline, paper);
        double ratio = (double) textInk / outlineInk;
        System.out.println((light ? "white" : "black") + " text" + (glyphVector ? " (GlyphVector)" : "") + ": route ink " + textInk + ", coverage ink " + outlineInk + ", ratio " + ratio);
        if (light && ratio < 1.25) {
            throw new RuntimeException("white text is not darkened like the platform's smoothing does: ratio " + ratio + " < 1.25");
        }
        if (!light && (ratio < 1.03 || ratio > 1.25)) {
            throw new RuntimeException("black text is not smoothed like the platform does: ratio " + ratio + " outside 1.03..1.25");
        }
    }

    enum Mode { STRING, GLYPH_VECTOR, OUTLINE }

    static BufferedImage render(Font font, Color ink, Color paper, Mode mode) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(paper);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(ink);
        g.setFont(font);
        FontRenderContext frc = g.getFontRenderContext();
        switch (mode) {
            case OUTLINE -> g.fill(font.createGlyphVector(frc, TEXT).getOutline(4, 26));
            case GLYPH_VECTOR -> g.drawGlyphVector(font.createGlyphVector(frc, TEXT), 4, 26);
            case STRING -> g.drawString(TEXT, 4, 26);
        }
        g.dispose();
        return image;
    }

    static long ink(BufferedImage image, Color paper) {
        long sum = 0;
        int p = paper.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int c = image.getRGB(x, y);
                for (int s = 0; s < 24; s += 8) sum += Math.abs(((c >> s) & 255) - ((p >> s) & 255));
            }
        }
        return sum;
    }
}
