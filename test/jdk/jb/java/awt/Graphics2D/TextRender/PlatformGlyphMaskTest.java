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
 * @summary The Skia scaler's glyph masks from the platform's own rasterizer on
 *          Windows (DirectWrite). Two routes share one contract: the coverage
 *          route (sun.java2d.skia.text.gray=platformCoverage) hands the pipes
 *          linear coverage, so the ink of antialiased text equals the area the
 *          outline covers (the outline filled by Java2D's own antialiasing is
 *          the reference) within a few percent, and is the same for light and
 *          dark text; the platform-smoothing route (=platform, the macOS cg
 *          route's name on Windows) renders through DirectWrite with the
 *          system's rendering parameters for the text's luminance, so light
 *          text comes out heavier than dark text by DirectWrite's own ratio
 *          (measured 1.13 to 1.16 for grayscale, 1.25 to 1.27 for ClearType on
 *          a default system). Before the routes existed, Skia's DirectWrite
 *          host handed out textures weighted for DirectWrite's blend as if
 *          they were coverage: 40 to 50 percent more ink than the outline's
 *          area, the same for both luminances, and the platform route
 *          returned false. The Segoe UI 13 line is measured with grayscale
 *          and with LCD antialiasing.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.font
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platformCoverage PlatformGlyphMaskTest coverage
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform PlatformGlyphMaskTest smoothed
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;

public class PlatformGlyphMaskTest {

    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 320, H = 32, X = 4, BASE = 22;

    public static void main(String[] args) {
        String route = args[0];
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        if (!font.getFamily().equalsIgnoreCase("Segoe UI")) {
            System.out.println("Segoe UI not installed: nothing to test");
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (Object aa : new Object[] {RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB}) {
            String mode = aa == RenderingHints.VALUE_TEXT_ANTIALIAS_ON ? "gray" : "lcd";
            // The LCD loops blend with their own gamma (KEY_TEXT_LCD_CONTRAST, 140 by default), which
            // weights light text over dark text by itself. A mask that already carries the platform's
            // weighting must be composited plainly (contrast 100, the linear blend) to show the
            // platform's ratio; the default contrast on top of it doubles the effect (printed).
            // With the platform route active the graphics state blends plainly whatever contrast
            // is asked for (SunGraphics2D.lcdTextContrast), so the hint is left at 140 here and the
            // ratio must still be DirectWrite's; the explicit 100 is printed as the control.
            double dark = ink(font, aa, Color.BLACK, Color.WHITE, 140);
            double light = ink(font, aa, Color.WHITE, Color.BLACK, 140);
            if (route.equals("smoothed") && mode.equals("lcd")) {
                System.out.printf("%s lcd at an explicit contrast 100: light/dark %.3f%n", route,
                                  ink(font, aa, Color.WHITE, Color.BLACK, 100) / ink(font, aa, Color.BLACK, Color.WHITE, 100));
            }
            double area = outlineArea(font);
            double ratio = light / dark;
            System.out.printf("%s %s: ink dark on light %.0f, light on dark %.0f (ratio %.3f), outline area %.0f (dark/area %.3f)%n",
                              route, mode, dark, light, ratio, area, dark / area);
            if (route.equals("coverage")) {
                if (Math.abs(dark / area - 1) > 0.08) {
                    failures.append(mode).append(": coverage ink is ").append(String.format("%.2f", dark / area)).append(" of the outline area; ");
                }
                // the LCD loops blend with their own gamma, so LCD ink depends on the text colour
                // on every scaler; only the grayscale mask can be checked for symmetry
                if (mode.equals("gray") && Math.abs(ratio - 1) > 0.03) {
                    failures.append(mode).append(": coverage depends on the text luminance (ratio ").append(String.format("%.3f", ratio)).append("); ");
                }
            } else {
                double low = mode.equals("gray") ? 1.06 : 1.12, high = mode.equals("gray") ? 1.25 : 1.40;
                if (ratio < low || ratio > high) {
                    failures.append(mode).append(": light/dark ink ratio ").append(String.format("%.3f", ratio))
                            .append(" is not DirectWrite's (").append(low).append("..").append(high).append("); ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Platform glyph masks (" + route + "): " + failures);
        }
        System.out.println("PASSED");
    }

    /** Sum of |pixel - paper| over the channel average, for the text drawn through the strike. */
    static double ink(Font font, Object aa, Color text, Color paper, int lcdContrast) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(paper);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, lcdContrast);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(text);
            g.setFont(font);
            g.drawString(TEXT, X, BASE);
        } finally {
            g.dispose();
        }
        return sum(image, paper);
    }

    /** The area the outlines cover: Java2D's own antialiased fill of the glyph outlines, black on white. */
    static double outlineArea(Font font) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                                                          RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            GlyphVector gv = font.createGlyphVector(frc, TEXT);
            g.setColor(Color.BLACK);
            g.fill(gv.getOutline(X, BASE));
        } finally {
            g.dispose();
        }
        return sum(image, Color.WHITE);
    }

    static double sum(BufferedImage image, Color paper) {
        double total = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                total += (Math.abs(((p >> 16) & 255) - paper.getRed()) + Math.abs(((p >> 8) & 255) - paper.getGreen())
                          + Math.abs((p & 255) - paper.getBlue())) / 3.0;
            }
        }
        return total;
    }
}
