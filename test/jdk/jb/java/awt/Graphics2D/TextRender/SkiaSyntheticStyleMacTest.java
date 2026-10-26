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
 * @summary macOS synthesizes nothing for a face a font lacks: CoreText and AppKit draw the
 *          regular glyphs for an italic request on Monaco or Fira Code (neither has an
 *          italic face) and for a bold request on Monaco. The Skia scaler route does the
 *          same: a fake-italic request renders exactly the regular glyphs. Today's platform
 *          scaler keeps the JDK's own convention, a 0.2 shear of the strike transform for
 *          the fake italic and no fake bold, which the platform run documents.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 SkiaSyntheticStyleMacTest platform
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia SkiaSyntheticStyleMacTest skia
 */
public class SkiaSyntheticStyleMacTest {
    static final int SIZE = 120, W = 200, H = 200;

    public static void main(String[] args) throws Exception {
        final String scaler = args[0];
        final boolean skia = scaler.equals("skia");
        for (String family : new String[]{"Monaco", "Fira Code"}) {
            if (!new Font(family, Font.PLAIN, 12).getFamily().equals(family)) {
                System.out.println(family + " is not available: skipped");
                continue;
            }
            Font plain = new Font(family, Font.PLAIN, SIZE), italic = new Font(family, Font.ITALIC, SIZE), bold = new Font(family, Font.BOLD, SIZE);
            BufferedImage p = render(plain), i = render(italic), b = render(bold);
            double shear = stemShear(i), plainShear = stemShear(p);
            int italicDiff = differing(p, i), boldDiff = differing(p, b);
            System.out.printf("%s on the %s scaler: italic stem shear %.4f (plain %.4f), italic differs from plain in %d px, bold in %d px%n",
                              family, scaler, shear, plainShear, italicDiff, boldDiff);
            if (Math.abs(plainShear) > 0.005) {
                throw new RuntimeException(family + ": the regular face is not upright: " + plainShear);
            }
            if (family.equals("Monaco") && boldDiff != 0) {   // Monaco has no bold face; Fira Code has one
                throw new RuntimeException(family + ": a fake bold appeared on the " + scaler + " scaler (" + boldDiff + " pixels differ); macOS draws the regular glyphs");
            }
            if (skia) {
                if (italicDiff != 0) {
                    throw new RuntimeException(family + ": a fake italic appeared on the Skia scaler (" + italicDiff + " pixels differ); macOS draws the regular glyphs");
                }
            } else if (Math.abs(shear - 0.2) > 0.005) {
                throw new RuntimeException(family + ": the platform scaler's fake italic shear is " + shear + ", the JDK's convention is 0.2");
            }
        }
    }

    static int differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
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
