/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * @test
 * @summary On macOS the layout attributes with a platform equivalent are laid out as TextKit 2 lays them out
 *          (sun.java2d.skia.text.attributes=system, the default; measured 2026-11-10 in an NSTextView,
 *          aktk2attributes.swift): a superscript or subscript keeps its glyphs' size and moves by TextKit 2's baseline
 *          offset for the level (Helvetica Neue 24: 12 up, 11 down; Avenir Next 24: 17 and 16), where Java scaled the
 *          font by (2/3)ⁿ and moved it 0.375 em; a stretch of underlined runs shares one underline at the deepest of
 *          their positions (a subscript's included) and the thickest of their thicknesses, where Java underlined each
 *          run with its own font (a 24-pt run beside a 48-pt one: its own band); a background fills the line's band,
 *          from the tallest run's ascent (no higher than the ascent rounded to a point) to the deepest run's descent,
 *          where Java filled each run's own ascent to descent and leading. Values in points at 8x, TextKit 2's.
 *          TextLayout on the loops with the JDK and the Skia scaler, and the paragraph route on a raster surface.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true TextLayoutAttributesTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia TextLayoutAttributesTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextLayoutAttributesTest paragraph
 */
public class TextLayoutAttributesTest {
    static final int S = 8;
    static boolean paragraph;
    static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) {
        paragraph = args[0].equals("paragraph");
        // superscripts: TextKit 2's offsets (NSBaselineOffset for NSSuperscript), the glyphs at their size
        Object[][] shifts = {{"Helvetica Neue", 1, -12.0}, {"Helvetica Neue", 2, -24.0}, {"Helvetica Neue", -1, 11.0}, {"Helvetica Neue", -2, 23.0},
                             {"Avenir Next", 1, -17.0}, {"Avenir Next", -1, 16.0}, {"Times New Roman", 1, -11.0}, {"Times New Roman", -1, 10.0}};
        for (Object[] c : shifts) {
            AttributedString as = new AttributedString("x  x");
            face(as, (String) c[0], 24, 0, 4);
            as.addAttribute(TextAttribute.SUPERSCRIPT, c[1], 3, 4);
            double[][] g = components(draw(as));
            check(c[0] + " 24 level " + c[1] + ": the shifted glyph's scale", (g[1][3] - g[1][2]) / (g[0][3] - g[0][2]), 1.0, 0.02);
            check(c[0] + " 24 level " + c[1] + ": its baseline shift", g[1][3] - g[0][3], (double) c[2], 0.13);
        }
        // underline stretches: every run of the stretch at the stretch's band
        Object[][] stretches = {
                {"24 + 48 underlined", 48f, 0, 4.750, 2.500},
                {"24 + subscript underlined", 24f, -1, 13.375, 1.250},
                {"24 + superscript underlined", 24f, 1, 2.375, 1.250},
        };
        for (Object[] c : stretches) {
            String text = "x     x     x     ";
            AttributedString as = new AttributedString(text), plain = new AttributedString(text);
            for (AttributedString a : new AttributedString[] {as, plain}) {
                face(a, "Helvetica Neue", 24, 0, text.length());
                a.addAttribute(TextAttribute.SIZE, c[1], 6, 12);
                if ((int) c[2] != 0) a.addAttribute(TextAttribute.SUPERSCRIPT, c[2], 6, 12);
            }
            as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            BufferedImage d = draw(as), p = draw(plain);
            TextLayout tl = new TextLayout(plain.getIterator(), new FontRenderContext(null, true, true));
            for (int r = 0; r < 2; r++) {
                int x1 = (int) ((tl.getCaretInfo(TextHitInfo.leading(r * 6 + 2))[0] + 20) * S), x2 = (int) ((tl.getCaretInfo(TextHitInfo.leading(r * 6 + 5))[0] + 20) * S);
                double sum = 0, moment = 0;
                for (int y = 0; y < d.getHeight(); y++) {
                    double cov = 0;
                    for (int x = x1; x < x2; x++) cov += Math.max(0, ink(d, x, y) - ink(p, x, y));
                    cov /= Math.max(1, x2 - x1);
                    sum += cov;
                    moment += cov * (y + 0.5);
                }
                check(c[0] + ", run " + (r + 1) + ": the underline's centre", moment / sum / S - 100, (double) c[3], 0.07);
                check(c[0] + ", run " + (r + 1) + ": its thickness", sum / S, (double) c[4], 0.07);
            }
        }
        // backgrounds: the line's band, TextKit 2's top and bottom
        Object[][] bands = {{"Helvetica Neue", 13, false, -12.0, 2.75}, {"Helvetica Neue", 13, true, -24.75, 5.5},
                            {"Times New Roman", 16, false, -14.0, 3.375}, {"Times New Roman", 16, true, -28.5, 6.875},
                            {"Menlo", 12, false, -11.0, 2.75}, {"Menlo", 12, true, -22.0, 5.625}};
        for (Object[] c : bands) {
            AttributedString as = new AttributedString("abcdef");
            face(as, (String) c[0], (int) c[1], 0, 6);
            if ((boolean) c[2]) as.addAttribute(TextAttribute.SIZE, (float) (2 * (int) c[1]), 4, 6);
            as.addAttribute(TextAttribute.BACKGROUND, Color.RED, 2, 4);
            BufferedImage img = draw(as);
            int top = Integer.MAX_VALUE, bottom = -1;
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                int px = img.getRGB(x, y);
                if ((px >> 16 & 0xff) > 230 && (px >> 8 & 0xff) < 25 && (px & 0xff) < 25) { top = Math.min(top, y); bottom = Math.max(bottom, y + 1); }
            }
            String what = c[0] + " " + c[1] + ((boolean) c[2] ? " beside a run twice its size" : "") + ": the background's ";
            check(what + "top", top / (double) S - 100, (double) c[3], 0.13);
            check(what + "bottom", bottom / (double) S - 100, (double) c[4], 0.13);
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static void check(String what, double seen, double expected, double tolerance) {
        System.out.printf("%s: %.3f (TextKit 2 %.3f)%n", what, seen, expected);
        if (!(Math.abs(seen - expected) <= tolerance)) {
            failures.append(String.format("%s: %.3f, TextKit 2's %.3f; ", what, seen, expected));
        }
    }

    /** FAMILY and SIZE: a FONT attribute would make TextLayout ignore the attributes beside it. */
    static void face(AttributedString as, String family, int size, int from, int to) {
        as.addAttribute(TextAttribute.FAMILY, family, from, to);
        as.addAttribute(TextAttribute.SIZE, (float) size, from, to);
    }

    static double ink(BufferedImage img, int x, int y) {
        return (255 - (img.getRGB(x, y) >> 8 & 0xff)) / 255.0;
    }

    static BufferedImage draw(AttributedString as) {
        BufferedImage img = new BufferedImage(600 * S, 200 * S, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(S, S);
        if (paragraph) {
            g.drawString(as.getIterator(), 20f, 100f);
        } else {
            new TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, 20f, 100f);
        }
        g.dispose();
        return img;
    }

    /** Ink components along x: left, right, top, bottom in points from (20, 100). */
    static double[][] components(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[] col = new double[w];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) col[x] += ink(img, x, y);
        List<double[]> out = new ArrayList<>();
        for (int x = 0; x < w; ) {
            if (col[x] < 0.05) { x++; continue; }
            int start = x;
            while (x < w && col[x] >= 0.05) x++;
            double[] rows = new double[h];
            for (int y = 0; y < h; y++) for (int c = start; c < x; c++) rows[y] += ink(img, c, y);
            int first = -1, last = -1;
            for (int y = 0; y < h; y++) if (rows[y] > 0.2) { if (first < 0) first = y; last = y; }
            if (first < 0) continue;
            double body = Math.max(rows[Math.min(last, first + 2)], rows[Math.max(first, last - 2)]);
            out.add(new double[] {start / (double) S - 20, x / (double) S - 20, (first + 1 - Math.min(1, rows[first] / body)) / S - 100, (last + Math.min(1, rows[last] / body)) / S - 100});
        }
        return out.toArray(new double[0][]);
    }
}
