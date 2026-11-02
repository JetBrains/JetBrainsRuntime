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
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * @test
 * @key headful
 * @summary Text decorations under the system convention (sun.java2d.skia.text.decorations=system,
 *          the default): an underline uses the font's own metrics on whole device pixel rows, placed
 *          as CoreText places it (Helvetica Neue 13 at 1x: one clear row under the glyph bottoms,
 *          then the underline), at
 *          least one pixel thick, and on macOS leaves gaps where glyph ink crosses it (CoreText skips
 *          the descenders). The same rule holds on Java's TextLayout decorations (the java2d strike:
 *          Decoration/Underline stroked Java's nominal size/18 offset at a fractional y through the
 *          descenders, over the baseline row) and on Skia's paragraphs (SkParagraph drew its own
 *          continuous decoration a row off), so the two routes render an underlined string alike
 *          (2026-11-02). At 2x the rows and the gaps' clearance scale with the device: one user pixel
 *          beside every descender, as CoreText's (the first version cleared one device pixel).
 * @requires (os.family == "mac" | os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia TextDecorationsTest java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia TextDecorationsTest paragraphs
 */
public class TextDecorationsTest {
    static final int W = 400, H = 60, BASELINE = 30;
    static final String TEXT = "jumpy gyp Quick";

    public static void main(String[] args) throws Exception {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL") && !gc.getClass().getName().contains("Dawn") && !gc.getClass().getName().contains("SK")) {
            throw new RuntimeException("not a Skia configuration: " + gc.getClass().getName());
        }
        Font font = new Font(mac ? "Helvetica Neue" : "Segoe UI", Font.PLAIN, 13);
        List<String> errors = new ArrayList<>();
        for (int scale : new int[] {1, 2}) {
            check(gc, font, scale, mac, errors);
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    /** The three renderings at a device scale (the graphics scaled, the baseline at BASELINE * scale), measured. */
    static void check(GraphicsConfiguration gc, Font font, int scale, boolean mac, List<String> errors) {
        final int W = TextDecorationsTest.W * scale, H = TextDecorationsTest.H * scale, BASELINE = TextDecorationsTest.BASELINE * scale;
        BufferedImage plainImg = render(gc, font, PLAIN, scale), img = render(gc, font, UNDERLINED, scale), struck = render(gc, font, STRUCK, scale);
        // the text's columns; the descender columns (ink below the baseline without the underline) and
        // their neighbourhood of one column each side, where a gapped underline leaves its clearance
        int textX1 = W, textX2 = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (ink(plainImg, x, y) > 0.1) { textX1 = Math.min(textX1, x); textX2 = Math.max(textX2, x); }
        boolean[] descender = new boolean[W], near = new boolean[W];
        for (int x = textX1; x <= textX2; x++) {
            for (int y = BASELINE + 1; y < H; y++) if (ink(plainImg, x, y) > 0.1) { descender[x] = true; break; }   // the antialiased edge columns count as ink
        }
        for (int x = textX1; x <= textX2; x++) {
            for (int d = -scale; d <= scale && !near[x]; d++) near[x] = x + d >= 0 && x + d < W && descender[x + d];   // one user pixel each side
        }
        int plain = 0, neighbourhood = 0;
        for (int x = textX1; x <= textX2; x++) { if (near[x]) neighbourhood++; else plain++; }
        // the underline row: the row at or below the baseline with the most ink added over the plain columns
        double[] added = new double[H];
        int ulRow = -1;
        for (int y = BASELINE; y < H; y++) {
            for (int x = textX1; x <= textX2; x++) if (!near[x]) added[y] += ink(img, x, y) - ink(plainImg, x, y);
            if (ulRow < 0 || added[y] > added[ulRow]) ulRow = y;
        }
        // the band: the rows from ulRow down that gain at least 90 % (Helvetica Neue 13's 0.65 px: one row at 1x, two at 2x)
        int bandRows = 1;
        while (ulRow + bandRows < H && added[ulRow + bandRows] >= plain * 0.9) bandRows++;
        double ulPlain = added[ulRow], abovePlain = ulRow > 0 ? added[ulRow - 1] : 0, belowPlain = ulRow + bandRows < H ? added[ulRow + bandRows] : 0;
        double addedNear = 0;
        for (int x = textX1; x <= textX2; x++) if (near[x]) addedNear += ink(img, x, ulRow) - ink(plainImg, x, ulRow);
        System.out.printf("%dx: underline row %d (baseline %d): ink added over the %d plain columns %.1f, the row above %.1f, below %.1f; over the %d columns around the %s descenders %.1f%n",
                          scale, ulRow, BASELINE, plain, ulPlain, abovePlain, belowPlain, neighbourhood, countTrue(descender), addedNear);
        if (ulPlain < plain * 0.9) {
            errors.add("the underline is not one solid row: row " + ulRow + " gains " + ulPlain + " over " + plain + " plain columns (a stroke smeared over rows, or no underline)");
        }
        if (abovePlain > plain * 0.15 || belowPlain > plain * 0.15) {
            errors.add("the underline spills over a neighbouring row: above " + abovePlain + ", below " + belowPlain + " of " + plain);
        }
        if (mac && bandRows != (scale == 1 ? 1 : 2)) {
            errors.add(scale + "x: the underline is " + bandRows + " rows thick; Helvetica Neue 13's 0.65 px thickness rounds up to " + (scale == 1 ? 1 : 2));
        }
        if (mac && scale == 1 && ulRow - BASELINE != 1) {
            errors.add("the underline sits " + (ulRow - BASELINE) + " rows below the baseline y; CoreText draws Helvetica Neue 13 one row below it, with the row under the glyph bottoms clear (measured 2026-11-02)");
        }
        if (mac && neighbourhood > 0 && addedNear > neighbourhood * 0.25) {
            errors.add(scale + "x: the underline runs through the descenders: " + addedNear + " added over the " + neighbourhood + " columns around them");
        }
        if (mac) {
            // the clearance beside every descender is one user pixel (CoreText's, measured at 1x and 2x): on the
            // underline row, the ink's edge from the coverage of its outermost column, the gap's edge from the
            // underline's partial column; the median clearance over all edges must be close to `scale` pixels
            List<Double> clearances = new ArrayList<>();
            for (int x = textX1; x <= textX2; x++) {
                if (ink(plainImg, x, ulRow) <= 0.05 || (x > 0 && ink(plainImg, x - 1, ulRow) > 0.05)) continue;
                int a = x, bEnd = x;
                while (bEnd + 1 < W && ink(plainImg, bEnd + 1, ulRow) > 0.05) bEnd++;
                double leftEdge = a + 1 - ink(plainImg, a, ulRow), rightEdge = bEnd + ink(plainImg, bEnd, ulRow);
                for (int c = a - 1; c >= textX1; c--) {
                    double u = ink(img, c, ulRow) - ink(plainImg, c, ulRow);
                    if (u > 0.02) { clearances.add(leftEdge - (c + u)); break; }
                }
                for (int c = bEnd + 1; c <= textX2; c++) {
                    double u = ink(img, c, ulRow) - ink(plainImg, c, ulRow);
                    if (u > 0.02) { clearances.add((c + 1 - u) - rightEdge); break; }
                }
                x = bEnd;
            }
            clearances.sort(null);
            double median = clearances.isEmpty() ? Double.NaN : clearances.get(clearances.size() / 2);
            System.out.printf("%dx: clearance beside the descenders over %d edges: median %.2f px (expected about %d)%n", scale, clearances.size(), median, scale);
            if (!(median >= 0.9 * scale && median <= 1.5 * scale)) {   // one device pixel of clearance at 2x measured 1.6 here
                errors.add(scale + "x: the underline's clearance beside the descenders is " + median + " device pixels, CoreText's is one user pixel (" + scale + ")");
            }
        }
        // the strikethrough over "gyp" alone, beside a coloured "Quick": one solid row above the baseline over
        // the struck characters' columns and nothing added elsewhere on that row (Skia's paragraph groups its
        // runs by font and colour, not by decoration: the decoration must follow the characters)
        FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int sx1 = (int) Math.ceil((10 + new TextLayout(TEXT.substring(0, 6), font, frc).getAdvance()) * scale) + 1;
        int sx2 = (int) Math.floor((10 + new TextLayout(TEXT.substring(0, 9), font, frc).getAdvance()) * scale) - 1;
        // the line crosses the glyph bodies: the row is the one gaining the most ink over the struck columns, and
        // it must be solid there in the struck rendering
        double[] addedStruck = new double[H], addedElsewhere = new double[H], inkStruck = new double[H];
        int stRow = -1;
        for (int y = BASELINE - 12 * scale; y < BASELINE; y++) {
            for (int x = textX1; x <= textX2; x++) {
                double d = ink(struck, x, y) - ink(plainImg, x, y);
                if (x >= sx1 && x <= sx2) { addedStruck[y] += d; inkStruck[y] += ink(struck, x, y); } else if (x < sx1 - 2 || x > sx2 + 2) addedElsewhere[y] += d;
            }
            if (stRow < 0 || addedStruck[y] > addedStruck[stRow]) stRow = y;
        }
        int struckColumns = sx2 - sx1 + 1, otherColumns = (textX2 - textX1 + 1) - (sx2 - sx1 + 5);
        System.out.printf("%dx: strikethrough row %d (baseline %d): ink over the %d struck columns %.1f (added %.1f, the row above %.1f, below %.1f); added over the %d other columns %.1f%n",
                          scale, stRow, BASELINE, struckColumns, inkStruck[stRow], addedStruck[stRow], addedStruck[stRow - 1], addedStruck[stRow + 1], otherColumns, addedElsewhere[stRow]);
        if (inkStruck[stRow] < struckColumns * 0.95 || addedStruck[stRow] < struckColumns * 0.3) {
            errors.add("the strikethrough is not one solid row over the struck characters: row " + stRow + " carries " + inkStruck[stRow] + " over " + struckColumns + " columns (" + addedStruck[stRow] + " added)");
        }
        if (addedStruck[stRow - 1] > struckColumns * 0.15 || addedStruck[stRow + 1] > struckColumns * 0.15) {
            errors.add("the strikethrough spills over a neighbouring row");
        }
        if (addedElsewhere[stRow] > otherColumns * 0.1) {
            errors.add("the strikethrough reaches characters that are not struck: " + addedElsewhere[stRow] + " added over the " + otherColumns + " other columns");
        }
    }

    static final int PLAIN = 0, UNDERLINED = 1, STRUCK = 2;

    static int countTrue(boolean[] a) {
        int n = 0;
        for (boolean b : a) if (b) n++;
        return n;
    }

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
