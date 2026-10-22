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
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary Characters the font lacks are drawn through the platform's font
 *          fallback, on the platform's font scaler and on the Skia one: CJK,
 *          Greek, Cyrillic, Hebrew, Arabic and an emoji drawn with the Dialog
 *          font have ink and non-empty bounds, at least a third of the ink of
 *          Latin text of the same length. On macOS the mapper hands such
 *          characters to CoreText as negative glyph codes; the Skia strike
 *          used to return empty glyphs for them.
 * @key headful
 * @run main/othervm -Dsun.java2d.uiScale=1 FallbackGlyphsTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia FallbackGlyphsTest
 */
public class FallbackGlyphsTest {
    static final String[] SAMPLES = {"日本語テキスト", "Ωμέγα αβγ", "Кириллица", "עברית", "عربي", "\uD83D\uDE00\uD83D\uDE80"};
    static final String LATIN = "Quick fox";

    public static void main(String[] args) {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 16);
        long latin = ink(font, LATIN) / LATIN.length();
        if (latin == 0) {
            throw new RuntimeException("no ink for Latin text");
        }
        for (String sample : SAMPLES) {
            long perChar = ink(font, sample) / sample.codePointCount(0, sample.length());
            FontRenderContext frc = new FontRenderContext(null, true, true);
            Rectangle2D bounds = font.createGlyphVector(frc, sample).getVisualBounds();
            System.out.printf("%s: %d ink per character (Latin %d), bounds %.1fx%.1f%n", sample, perChar, latin,
                              bounds.getWidth(), bounds.getHeight());
            if (perChar * 3 < latin || bounds.isEmpty()) {
                throw new RuntimeException("fallback text '" + sample + "' is missing: " + perChar
                                           + " ink per character vs " + latin + " for Latin, bounds " + bounds);
            }
        }
        System.out.println("PASSED");
    }

    /** The ink of the string at 2x in the software loops: the sum of darkness over a white image. */
    static long ink(Font font, String text) {
        BufferedImage image = new BufferedImage(600, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(2, 2);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, 4, 28);
        g.dispose();
        long ink = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                ink += 255 - (image.getRGB(x, y) & 0xff);
            }
        }
        return ink;
    }
}
