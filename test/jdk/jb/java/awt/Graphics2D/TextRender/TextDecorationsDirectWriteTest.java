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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import sun.font.FontStrikeDesc;
import sun.font.FontUtilities;
import sun.font.StrikeMetrics;

/**
 * @test
 * @key headful
 * @summary Text decorations under the system convention on Windows (sun.java2d.skia.text.decorations=system,
 *          the default) are placed as Direct2D's text renderer places them, measured with dwref --decorations
 *          at 96 and 192 dpi (2026-11-03): an underline's top is the font's underline position rounded to a
 *          device row and its height the font's thickness kept fractional, antialiased downward (Segoe UI 13 at
 *          1x: the row under the baseline at 0.76 coverage; at 2x: two rows below full, the third at 0.52); a
 *          strikethrough is the unsnapped rectangle from the font's strikethrough position (the top of the
 *          stroke) down by its thickness (Segoe UI 13 at 1x: rows -4 and -3 above the baseline at 0.38 and 0.25;
 *          at 2x: rows -7 and -6 at 0.75 and 0.62; Direct2D rounds coverage to eighths, this renderer does not).
 *          No gaps around the descenders (DirectWrite draws through the ink). The expectations are computed
 *          from the strike's own metrics so the test holds for any version of the font. Before this rule
 *          Windows got CoreText's whole rows (Segoe UI 13: one full row at 1x, two at 2x). Both routes: Java's
 *          TextLayout decorations and Skia's paragraphs.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.font:+open
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia TextDecorationsDirectWriteTest java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextDecorationsDirectWriteTest skia
 */
public class TextDecorationsDirectWriteTest {
    static final int W = 400, H = 60, BASELINE = 30;
    static final String TEXT = "jumpy gyp Quick";
    static final double TOLERANCE = 0.12;   // of coverage; Direct2D's own eighths are within it
    static final FontRenderContext FRC = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("Dawn") && !gc.getClass().getName().contains("SK")) {
            throw new RuntimeException("not a Skia configuration: " + gc.getClass().getName());
        }
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        if (!FontUtilities.getFont2D(font).getFamilyName(null).equals("Segoe UI")) {
            throw new RuntimeException("Segoe UI is not installed");
        }
        // the font's own decoration metrics as the Skia scaler reports them: the underline offset is the top of the
        // stroke below the baseline, the strikethrough offset the top of the stroke above it (negative; Skia's
        // DirectWrite host hands on DWRITE_FONT_METRICS.strikethroughPosition, the OS/2 table's top of the stroke)
        int aa = FontStrikeDesc.getAAHintIntVal(FRC.getAntiAliasingHint(), FontUtilities.getFont2D(font), font.getSize());
        int fm = FontStrikeDesc.getFMHintIntVal(FRC.getFractionalMetricsHint());
        Object strike = FontUtilities.getFont2D(font).getStrike(font, new AffineTransform(), aa, fm);
        Method getFontMetrics = Class.forName("sun.font.FontStrike").getDeclaredMethod("getFontMetrics");
        getFontMetrics.setAccessible(true);
        StrikeMetrics sm = (StrikeMetrics) getFontMetrics.invoke(strike);
        if (Float.isNaN(sm.underlineOffset) || !(sm.underlineThickness > 0) || Float.isNaN(sm.strikethroughOffset) || !(sm.strikethroughThickness > 0)) {
            throw new RuntimeException("the Skia scaler reports no decoration metrics for " + font);
        }
        System.out.printf("%s: underline top %.3f thickness %.3f, strikethrough top %.3f thickness %.3f (user px)%n",
                          font.getFontName(), sm.underlineOffset, sm.underlineThickness, sm.strikethroughOffset, sm.strikethroughThickness);
        List<String> errors = new ArrayList<>();
        for (int scale : new int[] {1, 2}) {
            check(gc, font, scale, sm, errors);
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    /** The coverage of the device row {@code [row, row + 1)} by the band {@code [top, top + height)}, 0..1. */
    static double rowCoverage(double top, double height, int row) {
        return Math.max(0, Math.min(row + 1, top + height) - Math.max(row, top));
    }

    static void check(GraphicsConfiguration gc, Font font, int scale, StrikeMetrics sm, List<String> errors) {
        final int W = TextDecorationsDirectWriteTest.W * scale, H = TextDecorationsDirectWriteTest.H * scale, BASELINE = TextDecorationsDirectWriteTest.BASELINE * scale;
        BufferedImage plainImg = render(gc, font, PLAIN, scale), underlined = render(gc, font, UNDERLINED, scale), struck = render(gc, font, STRUCK, scale);
        int textX1 = W, textX2 = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (ink(plainImg, x, y) > 0.1) { textX1 = Math.min(textX1, x); textX2 = Math.max(textX2, x); }

        // Direct2D's underline: the top row is the rounded position, the band the thickness, fractional
        double ulTop = BASELINE + Math.round(sm.underlineOffset * scale), ulHeight = sm.underlineThickness * scale;
        StringBuilder profile = new StringBuilder();
        for (int row = BASELINE; row < BASELINE + 6 * scale; row++) {
            double expected = rowCoverage(ulTop, ulHeight, row);
            double measured = coverage(underlined, plainImg, row, textX1, textX2);
            profile.append(String.format(" %+d:%.2f/%.2f", row - BASELINE, measured, expected));
            if (Math.abs(measured - expected) > TOLERANCE) {
                errors.add(String.format("%dx underline: row %+d below the baseline has coverage %.2f, DirectWrite's is %.2f (band [%.2f, %.2f) below the baseline row)",
                                         scale, row - BASELINE, measured, expected, ulTop - BASELINE, ulTop - BASELINE + ulHeight));
            }
        }
        System.out.printf("%dx underline rows (measured/DirectWrite):%s%n", scale, profile);

        // Direct2D's strikethrough: the unsnapped rectangle from the stroke's top down by the thickness, over the
        // struck characters "gyp" only (the paragraph route must keep it to its run)
        int sx1 = (int) Math.ceil((10 + new TextLayout(TEXT.substring(0, 6), font, FRC).getAdvance()) * scale) + 1;
        int sx2 = (int) Math.floor((10 + new TextLayout(TEXT.substring(0, 9), font, FRC).getAdvance()) * scale) - 1;
        double stTop = BASELINE + sm.strikethroughOffset * scale, stHeight = sm.strikethroughThickness * scale;
        profile.setLength(0);
        for (int row = BASELINE - 10 * scale; row < BASELINE; row++) {
            double expected = rowCoverage(stTop, stHeight, row);
            double measured = coverage(struck, plainImg, row, sx1, sx2);
            double elsewhere = Math.max(coverage(struck, plainImg, row, textX1, sx1 - 3), coverage(struck, plainImg, row, sx2 + 3, textX2));
            if (expected > 0 || measured > 0.05) profile.append(String.format(" %+d:%.2f/%.2f", row - BASELINE, measured, expected));
            if (Math.abs(measured - expected) > TOLERANCE) {
                errors.add(String.format("%dx strikethrough: row %+d has coverage %.2f over the struck characters, DirectWrite's is %.2f (band [%.2f, %.2f) from the baseline row)",
                                         scale, row - BASELINE, measured, expected, stTop - BASELINE, stTop - BASELINE + stHeight));
            }
            if (elsewhere > 0.1) {
                errors.add(String.format("%dx strikethrough: row %+d gains %.2f over characters that are not struck", scale, row - BASELINE, elsewhere));
            }
        }
        System.out.printf("%dx strikethrough rows (measured/DirectWrite):%s%n", scale, profile);
    }

    /** The mean ink added by the decorated rendering on a row over the columns the plain rendering leaves clear there. */
    static double coverage(BufferedImage decorated, BufferedImage plain, int row, int x1, int x2) {
        double sum = 0;
        int n = 0;
        for (int x = x1; x <= x2; x++) {
            if (ink(plain, x, row) > 0.02) continue;
            sum += ink(decorated, x, row);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    static final int PLAIN = 0, UNDERLINED = 1, STRUCK = 2;

    /** The text on a surface of the configuration, black on white with a blue "Quick": plain, underlined, or "gyp" struck through. */
    static BufferedImage render(GraphicsConfiguration gc, Font font, int mode, int scale) {
        VolatileImage vi = gc.createCompatibleVolatileImage(W * scale, H * scale, Transparency.OPAQUE);
        BufferedImage img;
        do {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W * scale, H * scale);
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            as.addAttribute(TextAttribute.FOREGROUND, Color.BLUE, 10, TEXT.length());   // a second run of the paragraph
            if (mode == UNDERLINED) {
                as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            } else if (mode == STRUCK) {
                as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, 6, 9);
            }
            g.drawString(as.getIterator(), 10, BASELINE);
            g.dispose();
            img = vi.getSnapshot();
        } while (vi.contentsLost());
        return img;
    }

    /** Ink 0..1: how far the darkest channel is from white (black and blue text both count as full ink). */
    static double ink(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return (255 - Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff))) / 255.0;
    }
}
