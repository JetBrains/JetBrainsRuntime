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
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary Synthetic styles on macOS follow today's JDK, since CoreText and AppKit
 *          synthesize nothing for a face a font lacks: a system font without an italic
 *          face (Monaco) is sheared by 0.2 through the strike transform on the Skia
 *          scaler as on the platform scaler, and gets no fake bold on either; a bundled
 *          file font without an italic face (Fira Code) is a CFont on macOS as well and
 *          gets the same 0.2 shear, not FreeType's 0.2126.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 SkiaSyntheticStyleMacTest platform
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia SkiaSyntheticStyleMacTest skia
 */
public class SkiaSyntheticStyleMacTest {
    static final int SIZE = 120, W = 200, H = 200;

    public static void main(String[] args) throws Exception {
        final String scaler = args[0];
        check("Monaco", 0.2, 0.005, scaler);                 // system font: the 0.2 shear of CFont.createStrike
        if (new Font("Fira Code", Font.PLAIN, 12).getFamily().equals("Fira Code")) {
            // a bundled file font is a CFont on macOS too: the same 0.2 shear, not FreeType's 0.2126
            check("Fira Code", 0.2, 0.005, scaler);
        } else {
            System.out.println("Fira Code is not bundled in this image: file-font case skipped");
        }
    }

    static void check(String family, double expectedShear, double tolerance, String scaler) {
        Font plain = new Font(family, Font.PLAIN, SIZE), italic = new Font(family, Font.ITALIC, SIZE), bold = new Font(family, Font.BOLD, SIZE);
        if (!italic.getFontName().contains("Derived") && !italic.getFontName().toLowerCase().contains("italic")) {
            System.out.println(family + ": italic resolves to " + italic.getFontName());
        }
        double shear = stemShear(render(italic));
        double plainShear = stemShear(render(plain));
        System.out.printf("%s on the %s scaler: italic stem shear %.4f (plain %.4f), expected %.4f%n", family, scaler, shear, plainShear, expectedShear);
        if (Math.abs(shear - expectedShear) > tolerance || Math.abs(plainShear) > 0.01) {
            throw new RuntimeException(family + ": italic stem shear " + shear + ", expected " + expectedShear + " on the " + scaler + " scaler");
        }
        if (family.equals("Monaco")) {
            BufferedImage p = render(plain), b = render(bold);
            int diff = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (p.getRGB(x, y) != b.getRGB(x, y)) diff++;
            System.out.println(family + ": bold differs from plain in " + diff + " pixels (no fake bold on macOS)");
            if (diff != 0) {
                throw new RuntimeException(family + ": a fake bold appeared on the " + scaler + " scaler (" + diff + " pixels differ)");
            }
        }
    }

    /** The vertical bar at a large size, black on white: a straight stem (Monaco's 'l' has a hooked foot). */
    static BufferedImage render(Font font) {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString("|", 60, 150);
        g.dispose();
        return im;
    }

    /** Horizontal shift of the stem's ink centroid per pixel of height, between two rows of the stem. */
    static double stemShear(BufferedImage im) {
        double c1 = centre(im, 150), c2 = centre(im, 70);   // rows well inside the bar, 80 px apart
        return (c2 - c1) / 80.0;
    }

    static double centre(BufferedImage im, int row) {
        double sum = 0, weight = 0;
        for (int x = 0; x < W; x++) {
            double ink = 255 - (im.getRGB(x, row) & 0xff);
            sum += ink * x;
            weight += ink;
        }
        if (weight == 0) throw new RuntimeException("no ink in row " + row);
        return sum / weight;
    }
}
