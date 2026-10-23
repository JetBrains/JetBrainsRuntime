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
 * @summary A bold or italic style a font has no face for is synthesized by the
 *          scaler, and the Skia scaler must synthesize it the way the JDK's
 *          FreeType scaler does, or text laid out with the one shifts against
 *          the other (IDE scenes, 2026-10-23: dialogs and tree rows moved, CJK
 *          fallback text in italic comments leaned three times as much).
 *          FreeType (freetypeScaler.c): the oblique shear is FreeType's own
 *          0x366A / 0x10000 = 0.2126 (12 degrees) whatever the strike's
 *          italic value, and the synthetic bold emboldens the outline by
 *          1/32 em and adds that much to every advance. The Skia scaler used
 *          the strike's italic value 0.7 as the shear and left the advances
 *          of emboldened glyphs unchanged. Measured on the bundled Inter
 *          Regular derived bold and italic: the slant of a tall glyph (ink
 *          shift between its top and bottom quarters over their distance)
 *          and the width of a line in the bold style against the plain one.
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia SkiaSyntheticStyleTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform SkiaSyntheticStyleTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;

public class SkiaSyntheticStyleTest {

    static final String LINE = "Appearance & Behavior";
    static final double FREETYPE_SHEAR = 0x366A / 65536.0;   // FT_GlyphSlot_Oblique's matrix, used by the JDK's scaler
    static final double BOLD_EM_FRACTION = 1.0 / 32;         // the JDK's BOLD_DIVISOR

    public static void main(String[] args) throws Exception {
        File file = new File(System.getProperty("java.home"), "lib/fonts/Inter-Regular.otf");
        if (!file.isFile()) {
            System.out.println("no bundled Inter Regular: nothing to test");
            return;
        }
        Font regular = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(48f);
        StringBuilder failures = new StringBuilder();

        // synthetic italic: the shear of a tall straight glyph
        double upright = slant(regular, "H"), italic = slant(regular.deriveFont(Font.ITALIC), "H");
        double shear = italic - upright;
        System.out.printf("synthetic italic shear %.3f (upright glyph %.3f, italic %.3f); FreeType's %.4f%n", shear, upright, italic, FREETYPE_SHEAR);
        if (Math.abs(shear - FREETYPE_SHEAR) > 0.04) {
            failures.append(String.format("synthetic italic shear %.3f, the JDK's FreeType scaler shears by %.4f; ", shear, FREETYPE_SHEAR));
        }

        // synthetic bold: the advance of every glyph with ink (not the spaces) grows by 1/32 em;
        // measured exactly with fractional metrics. Whole-pixel advances are rounded per glyph
        // (FreeType from its hinted integer advance, so nothing grows below 16 px and a pixel
        // per glyph from there), which is only checked to lie within that range.
        int inked = LINE.replace(" ", "").length();
        for (double scale : new double[] {1, 2}) {
            for (boolean fractional : new boolean[] {false, true}) {
                Font plain = regular.deriveFont(13f), bold = plain.deriveFont(Font.BOLD);
                FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), true, fractional);
                double plainWidth = plain.getStringBounds(LINE, frc).getWidth() * scale;
                double boldWidth = bold.getStringBounds(LINE, frc).getWidth() * scale;
                double grown = boldWidth - plainWidth, expected = inked * 13 * scale * BOLD_EM_FRACTION;
                System.out.printf("synthetic bold at %sx %s: line %.2f px bold against %.2f px plain, grown by %.2f; FreeType grows it by %.2f (%d inked glyphs)%n",
                                  scale, fractional ? "fractional" : "whole-pixel", boldWidth, plainWidth, grown, expected, inked);
                boolean ok = fractional ? Math.abs(grown - expected) <= 1.0 : grown >= -0.5 && grown <= inked + 0.5;
                if (!ok) {
                    failures.append(String.format("synthetic bold at %sx %s widens the line by %.2f px, the JDK's FreeType scaler by %.2f; ",
                                                  scale, fractional ? "fractional" : "whole-pixel", grown, expected));
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Synthetic styles: " + failures);
        }
        System.out.println("PASSED");
    }

    /** Horizontal shift of the ink centroid between the top and the bottom quarter of the glyph, over their distance. */
    static double slant(Font font, String text) {
        int w = 96, h = 96;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            g.drawString(text, 24, 72);
        } finally {
            g.dispose();
        }
        int top = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((image.getRGB(x, y) & 255) < 128) {
                    if (top < 0) top = y;
                    bottom = y;
                }
            }
        }
        if (top < 0) {
            throw new RuntimeException("no ink for " + text);
        }
        int quarter = Math.max(1, (bottom - top + 1) / 4);
        double topCentroid = centroid(image, top, top + quarter), bottomCentroid = centroid(image, bottom - quarter + 1, bottom + 1);
        double distance = (bottom - quarter + 1 + bottom + 1) / 2.0 - (top + top + quarter) / 2.0;
        return (topCentroid - bottomCentroid) / distance;
    }

    static double centroid(BufferedImage image, int y0, int y1) {
        double sum = 0, weight = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                double ink = 255 - (image.getRGB(x, y) & 255);
                sum += ink * (x + 0.5);
                weight += ink;
            }
        }
        return weight == 0 ? 0 : sum / weight;
    }
}
