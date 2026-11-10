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

package sun.font;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Text decorations as the operating system's text draws them (the text settings' {@code decorations=system},
 * {@link sun.java2d.skia.SKEnvironment#textDecorations()}), by the platform's {@link Rule}: under CoreText's a
 * line occupies whole device pixel rows — its top row is the rounded top of the stroked line, its thickness at
 * least one pixel — and an underline leaves a gap wherever glyph ink crosses the line, with a clearance of one
 * device pixel on each side (CoreText skips the descenders); under Direct2D's the underline's top is rounded to
 * a row but its height is the font's thickness kept fractional and antialiased, the strikethrough is the
 * unsnapped rectangle of the font's metrics, and nothing is skipped (DirectWrite draws through the ink). The
 * same rule, in the same arithmetic, draws the decorations of Skia's paragraphs ({@code SKBackend.cpp}, the
 * paragraph visitor), so Java's TextLayout and SkParagraph render an underlined string alike. Under any transform
 * the band is the one the rule makes at the scale across the baseline, in the text's own frame, filled through the
 * transform with antialiasing, from the baseline itself — under CoreText's rule a fractional baseline splits the
 * band over two rows, as AppKit and CTLineDraw draw it while the glyphs snap to the rows — or, under Direct2D's,
 * from the glyphs' row. CoreText's behaviour, measured under rotations, shears, scales and fractional baselines
 * (2026-11-10).
 */
public final class TextDecorations {
    private TextDecorations() {}

    /**
     * How the operating system's text places a decoration (the native side's {@code SKTextDefaults.decorationRule},
     * {@link sun.java2d.skia.SKEnvironment#decorationRule()}).
     */
    public enum Rule {
        /** CoreText's: whole device rows, the thickness rounded up, the band never nearer the baseline than its height (measured 2026-11-02). */
        CORE_TEXT,
        /**
         * Direct2D's text renderer's, the one every DirectWrite application gets (dwref --decorations, measured
         * at 96 to 192 dpi, 2026-11-03): the underline's top row is the font's underline position rounded, its
         * height the font's thickness, fractional and antialiased downward; the strikethrough is the unsnapped
         * rectangle from the font's strikethrough position (the top of the stroke) down by its thickness.
         */
        DIRECT_WRITE
    }

    /**
     * Whether a character is line-end whitespace CoreText leaves undecorated at either end of a line: the space
     * separators (Unicode Zs: the no-break, em and ideographic spaces too) and the line terminators; a tab is ink
     * (measured 2026-11-10 with CTLineDraw: "x   " underlined under the x alone, "\tx\t" over the tabs too).
     * SKBackend.cpp's lineEndWhitespace is the same set.
     */
    public static boolean lineEndWhitespace(char c) {
        return Character.getType(c) == Character.SPACE_SEPARATOR || c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /**
     * The x range a label's underline and strikethrough span, from {@code x1} (its origin) to {@code x2}: under
     * CoreText's rule without the line's leading and trailing whitespace ({@link Decoration.Label#getInkSpan}),
     * null for the whole range, an empty array for none.
     */
    static float[] decorationSpan(Decoration.Label label, float x1, float x2) {
        if (rule() != Rule.CORE_TEXT) {
            return null;
        }
        float[] ink = label.getInkSpan();
        return ink == null || ink.length == 0 ? ink : new float[] {x1 + ink[0], x1 + ink[1]};
    }

    /** The horizontal clearance around ink crossing an underline, in user-space pixels (CoreText's: one point, two device pixels at 2x). */
    static final double CLEARANCE = 1.0;

    static Rule rule() {
        return sun.java2d.skia.SKEnvironment.decorationRule();
    }

    /**
     * The transform's scale across the baseline: device pixels per user unit perpendicular to the user x axis
     * (the determinant over the baseline's length), the scale the rule's rows are counted at. CoreText's band
     * under a rotation, a shear or a rotation with a scale is the one it draws axis-aligned at that scale (rotated
     * by 2 to 90 degrees, sheared by 0.25, at 1x to 2x: the same centre within 0.01 of a pixel), antialiased.
     */
    static double scaleAcross(AffineTransform at) {
        double length = Math.hypot(at.getScaleX(), at.getShearY());
        return length == 0 ? 0 : Math.abs(at.getDeterminant()) / length;
    }

    /**
     * The user-space offset from the baseline {@code y} to where the rule places a decoration's band: under
     * Direct2D's rule the nearest device row (a baseline along the rows) or column (along the columns), where the
     * glyphs' images are placed; 0 for a baseline across them, and always under CoreText's — AppKit and CTLineDraw
     * leave the band at the line's fractional baseline while the glyphs snap to the rows (measured 2026-11-10: an
     * NSView's decorations antialiased over two rows, CTLineDraw's band moving with the baseline by quarters).
     */
    static double baselineSnap(AffineTransform at, double y) {
        if (rule() == Rule.CORE_TEXT) {
            return 0;
        }
        if (at.getShearY() == 0 && at.getScaleY() != 0) {
            double device = at.getScaleY() * y + at.getTranslateY();
            return (Math.round(device) - device) / at.getScaleY();
        }
        if (at.getScaleX() == 0 && at.getShearX() != 0) {
            double device = at.getShearX() * y + at.getTranslateX();
            return (Math.round(device) - device) / at.getShearX();
        }
        return 0;
    }

    /** The user x of a band's end at {@code x}, moved onto a device pixel edge when the transform keeps the axes. */
    static double roundedEnd(AffineTransform at, double x) {
        if (at.getShearY() == 0 && at.getShearX() == 0 && at.getScaleX() != 0) {
            return (Math.round(at.getScaleX() * x + at.getTranslateX()) - at.getTranslateX()) / at.getScaleX();
        }
        if (at.getScaleX() == 0 && at.getScaleY() == 0 && at.getShearY() != 0) {
            return (Math.round(at.getShearY() * x + at.getTranslateY()) - at.getTranslateY()) / at.getShearY();
        }
        return x;
    }

    /**
     * The decoration metrics behind the core metrics, in user space: {underline position (CoreText's, the centre of
     * the stroke below the baseline, positive below), underline thickness, strikethrough centre (negative above the
     * baseline), strikethrough thickness, ascent, descent}. Under the system convention the core metrics carry the
     * font's own values (Font2D.getFontMetrics, from the scaler's StrikeMetrics), else Java's nominal ones; both as
     * offsets to the stroke's centre. The paragraph route (SKBackend.cpp, drawSystemDecorations) resolves the same
     * values from Skia's font metrics.
     */
    public static float[] fontMetrics(CoreMetrics cm) {
        return new float[] {cm.underlineOffset - cm.underlineThickness / 2, cm.underlineThickness, cm.strikethroughOffset, cm.strikethroughThickness,
                            cm.ascent, cm.descent};
    }

    /**
     * The strikethrough's centre (negative above the baseline) from the scaler's strikethrough offset and
     * thickness. Skia's font metrics name the offset the distance to the bottom of the stroke, and CoreText's
     * placement was fitted on that reading; Skia's DirectWrite host hands on DWRITE_FONT_METRICS.strikethroughPosition,
     * the OS/2 table's top of the stroke, and Direct2D draws the stroke from there downward (measured 2026-11-03).
     */
    public static float strikethroughCentre(float offset, float thickness) {
        return rule() == Rule.DIRECT_WRITE ? offset + thickness / 2 : offset - thickness / 2;
    }

    /** The bottom of the underline's band in user space at scale 1, for bounds (the rows at other scales lie within it). */
    public static float underlineBottom(float[] fontMetrics) {
        double[] band = underlineBand(fontMetrics, 1);
        return (float) Math.ceil(band[0] + band[1] - 1e-3);
    }

    /**
     * The device band of the underline of {@link #fontMetrics} (user space) at {@code scale} device pixels per unit,
     * as {offset of the band's top from the baseline, height} in device pixels; the position is CoreText's underline
     * position, the centre of the stroke.
     * <p>
     * CoreText's (string drawing and TextKit 2 alike; 893 of 893 cases over 30 fonts at 1x and 2x and 4730 of 4730
     * on a font with patched metrics at 1x, 2x, 3x and 16x, 2026-11-10): whole rows, the thickness rounded up, the
     * band rounded about the position, at least its own height below the baseline, a one-row line two rows below
     * once the position is past the first row and the descent more than four rows; the band's bottom within the
     * descent, which under 2 points is taken as 0.3 of the ascent and descent; a band that still cannot sit there
     * loses its last row (more than four rows of descent: the descent no more than its height; fewer: the band
     * reaching more than one and a half rows past it). A line under 0.35 device pixels stays unsnapped, about the
     * position and at least a row below the baseline.
     * <p>
     * Direct2D's: the top rounded to a row, the height the thickness itself (Segoe UI 13 at 1x: the row under the
     * baseline at 0.76 coverage); its position is the top of the stroke.
     */
    static double[] underlineBand(float[] fontMetrics, double scale) {
        double position = fontMetrics[0] * scale, thickness = fontMetrics[1] * scale;
        if (rule() == Rule.DIRECT_WRITE) {
            return new double[] {Math.round(position), thickness};
        }
        if (thickness < 0.35) {
            return new double[] {Math.max(1, position - thickness / 2), thickness};
        }
        // the tolerance keeps a value landing on a row by a product of scales (6 as 5.9999999 under a rotation with
        // a scale) on that row, alike on the paragraph route's float arithmetic
        int rows = Math.max(1, (int) Math.ceil(thickness - 1e-3));
        double descent = fontMetrics[5], room = (descent >= 2 ? descent : 0.3 * (fontMetrics[4] + descent)) * scale;
        int top = Math.min((int) Math.floor(position + 0.5 - rows / 2.0 + 1e-3), (int) Math.floor(room + 1e-3) - rows);
        top = Math.max(top, rows == 1 && position > 1 && room > 4 ? 2 : rows);
        if (rows >= 2 && (room > 4 ? room <= rows : room <= 2 * rows - 1.5)) {
            rows--;
        }
        return new double[] {top, rows};
    }

    /**
     * Draws an underline from {@code x1} to {@code x2} under the baseline {@code y} (user space) by
     * {@link #underlineBand} at the scale across the baseline; with {@code outline} (the glyphs' outline in user
     * space) the line leaves gaps where the ink crosses it, {@link #CLEARANCE} clear on each side, the gap's edges
     * antialiased at their fractional positions as CoreText's are (the rows stay whole on a baseline along them).
     */
    public static void drawUnderline(Graphics2D g2d, float x1, float x2, float y, float[] fontMetrics, Shape outline) {
        AffineTransform at = g2d.getTransform();
        double scale = scaleAcross(at);
        if (scale == 0) {
            return;
        }
        double[] band = underlineBand(fontMetrics, scale);
        double top = y + baselineSnap(at, y) + band[0] / scale, height = band[1] / scale;
        List<double[]> gaps = outline != null ? inkCrossings(outline, null, top, top + height) : List.of();
        fillBand(g2d, roundedEnd(at, x1), roundedEnd(at, x2), top, height, gaps, CLEARANCE);
    }

    /**
     * Draws a strikethrough from {@code x1} to {@code x2} centred {@code offset} (user space, negative above)
     * from the baseline {@code y}, by {@link #strikethroughBand} at the scale across the baseline.
     */
    public static void drawStrikethrough(Graphics2D g2d, float x1, float x2, float y, float offset, float thickness) {
        AffineTransform at = g2d.getTransform();
        double scale = scaleAcross(at);
        if (scale == 0) {
            return;
        }
        double[] band = strikethroughBand(offset * scale, thickness * scale);
        fillBand(g2d, roundedEnd(at, x1), roundedEnd(at, x2), y + baselineSnap(at, y) + band[0] / scale, band[1] / scale, List.of(), 0);
    }

    /**
     * The device band of a strikethrough centred {@code centre} (device pixels, negative above the baseline) with
     * {@code thickness}, as {offset of the band's top from the baseline, height}: under CoreText's rule whole rows,
     * the thickness rounded up as the underline's, the band rounded about the centre (AppKit's, with the scalers'
     * metrics on macOS: half the x-height, the underline's thickness; 32 of 32 cases at 1x and 2x, 2026-11-10);
     * under Direct2D's the unsnapped rectangle about the centre.
     */
    static double[] strikethroughBand(double centre, double thickness) {
        if (rule() == Rule.DIRECT_WRITE) {
            return new double[] {centre - thickness / 2, thickness};
        }
        long rows = Math.max(1, (long) Math.ceil(thickness - 1e-3));
        return new double[] {Math.round(centre - rows / 2.0), rows};
    }

    /**
     * The underline's band as {@link #drawUnderline} draws it under the transform {@code at} (a layout's render
     * context's), in user space from the baseline {@code y}: the band at the transform's scale across the baseline,
     * without the gaps where ink crosses it (the outline holds the glyphs there anyway). Under Direct2D's rule a
     * baseline off the device rows draws the band on the glyphs' row, as the glyphs' images.
     */
    public static Shape underlineShape(float x1, float x2, float y, float[] fontMetrics, AffineTransform at) {
        double scale = scaleOf(at);
        double[] band = underlineBand(fontMetrics, scale);
        return new Rectangle2D.Double(x1, y + band[0] / scale, x2 - x1, band[1] / scale);
    }

    /** The strikethrough's band as {@link #drawStrikethrough} draws it under the transform {@code at} (see underlineShape). */
    public static Shape strikethroughShape(float x1, float x2, float y, float offset, float thickness, AffineTransform at) {
        double scale = scaleOf(at);
        double[] band = strikethroughBand(offset * scale, thickness * scale);
        return new Rectangle2D.Double(x1, y + band[0] / scale, x2 - x1, band[1] / scale);
    }

    private static double scaleOf(AffineTransform at) {
        double scale = at != null ? scaleAcross(at) : 1;
        return scale > 0 ? scale : 1;
    }

    /**
     * Fills the band {@code [top, top + height)} from {@code x1} to {@code x2} (user space, the band along the user x
     * axis) through the graphics' transform, antialiased, skipping the gaps widened by {@code clearance} (user
     * space); a gap's edges are fractional and antialiased, as CoreText's.
     */
    static void fillBand(Graphics2D g2d, double x1, double x2, double top, double height, List<double[]> gaps, double clearance) {
        if (x2 <= x1 || height <= 0) {
            return;
        }
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            double start = x1;
            for (double[] gap : gaps) {
                double gx1 = gap[0] - clearance, gx2 = gap[1] + clearance;
                if (gx2 <= start || gx1 >= x2) continue;
                if (gx1 > start) g2d.fill(new Rectangle2D.Double(start, top, gx1 - start, height));
                start = Math.max(start, gx2);
            }
            if (start < x2) g2d.fill(new Rectangle2D.Double(start, top, x2 - start, height));
        } finally {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : savedAA);
        }
    }

    /**
     * The x ranges where the outline crosses the band {@code [yTop, yBottom)} (in the space {@code toDevice}, may
     * be null, maps the outline to): every segment of the
     * flattened outline that enters the band contributes the x extent of its part inside it; the ranges are
     * sorted and merged.
     */
    static List<double[]> inkCrossings(Shape outline, AffineTransform toDevice, double yTop, double yBottom) {
        List<double[]> ranges = new ArrayList<>();
        PathIterator it = outline.getPathIterator(toDevice, 0.25);
        double[] c = new double[6];
        double mx = 0, my = 0, px = 0, py = 0;
        boolean have = false;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> { mx = px = c[0]; my = py = c[1]; have = true; }
                case PathIterator.SEG_LINETO -> { if (have) crossing(ranges, px, py, c[0], c[1], yTop, yBottom); px = c[0]; py = c[1]; }
                case PathIterator.SEG_CLOSE -> { if (have) crossing(ranges, px, py, mx, my, yTop, yBottom); px = mx; py = my; }
                default -> { px = c[0]; py = c[1]; }   // flattened: no curves arrive
            }
            it.next();
        }
        ranges.sort((p, q) -> Double.compare(p[0], q[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
            } else {
                merged.add(new double[] {r[0], r[1]});
            }
        }
        return merged;
    }

    /** The x extent of the part of the segment inside the band, if any. */
    static void crossing(List<double[]> ranges, double x0, double y0, double x1, double y1, double yTop, double yBottom) {
        double lo = Math.min(y0, y1), hi = Math.max(y0, y1);
        if (hi <= yTop || lo >= yBottom) {   // strictly inside the band: glyph bottoms resting on its edge do not cross
            return;
        }
        double xa = x0, xb = x1;
        if (y0 != y1) {
            // clip the segment to the band
            double t0 = Math.max(0, Math.min(1, (yTop - y0) / (y1 - y0))), t1 = Math.max(0, Math.min(1, (yBottom - y0) / (y1 - y0)));
            xa = x0 + (x1 - x0) * Math.min(t0, t1);
            xb = x0 + (x1 - x0) * Math.max(t0, t1);
        }
        ranges.add(new double[] {Math.min(xa, xb), Math.max(xa, xb)});
    }
}
