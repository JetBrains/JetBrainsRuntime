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
import java.awt.geom.Point2D;
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
 * paragraph visitor), so Java's TextLayout and SkParagraph render an underlined string alike. Only translations
 * and uniform scales are snapped; under a rotation or shear the stroked line is drawn as before.
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

    /** The horizontal clearance around ink crossing an underline, in user-space pixels (CoreText's: one point, two device pixels at 2x). */
    static final double CLEARANCE = 1.0;

    static Rule rule() {
        return sun.java2d.skia.SKEnvironment.decorationRule();
    }

    /** Whether the graphics' transform lets a device-aligned line be drawn: a translation and a uniform scale. */
    public static boolean snappable(Graphics2D g2d) {
        AffineTransform at = g2d.getTransform();
        return at.getShearX() == 0 && at.getShearY() == 0 && at.getScaleX() == at.getScaleY() && at.getScaleX() > 0;
    }

    /**
     * The decoration metrics behind the core metrics, in user space: {underline position (baseline to the top
     * of the stroke, positive below), underline thickness, strikethrough centre (negative above the baseline),
     * strikethrough thickness}. Under the system convention the core metrics carry the font's own values
     * (Font2D.getFontMetrics, from the scaler's StrikeMetrics), else Java's nominal ones; both as offsets to the
     * stroke's centre. The paragraph route (SKBackend.cpp, drawSystemDecorations) resolves the same values from
     * Skia's font metrics.
     */
    public static float[] fontMetrics(CoreMetrics cm) {
        return new float[] {cm.underlineOffset - cm.underlineThickness / 2, cm.underlineThickness, cm.strikethroughOffset, cm.strikethroughThickness};
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
        double[] band = underlineBand(fontMetrics[0], fontMetrics[1]);
        return (float) Math.ceil(band[0] + band[1] - 1e-3);
    }

    /**
     * The device band of an underline whose metrics (device pixels) put the top of the stroke {@code position}
     * below the baseline with {@code thickness}, as {offset of the band's top from the baseline y, height}; the
     * baseline y is the glyphs' bottom edge, the row above it holds their bottoms. CoreText's placement, measured
     * over 34 fonts and sizes at 1x (2026-11-02): whole rows, the thickness rounded up, the row holding the
     * stroke's top for one row and the band growing upwards from there for more, never nearer the baseline than
     * its own height (the row under the glyph bottoms stays clear). Direct2D's: the top rounded to a row, the
     * height the thickness itself (Segoe UI 13 at 1x: the row under the baseline at 0.76 coverage).
     */
    public static double[] underlineBand(double position, double thickness) {
        if (rule() == Rule.DIRECT_WRITE) {
            return new double[] {Math.round(position), thickness};
        }
        int rows = Math.max(1, (int) Math.ceil(thickness - 1e-3));
        int top = Math.max(rows, (int) Math.floor(position + 0.5 - rows / 2.0));
        return new double[] {top, rows};
    }

    /**
     * Draws an underline from {@code x1} to {@code x2} under the baseline {@code y} (user space) by
     * {@link #underlineBand}; with {@code outline} (the glyphs' outline in user space) the line leaves gaps
     * where the ink crosses it, one device pixel clear on each side, the gap's edges antialiased at their
     * fractional positions as CoreText's are (the rows stay whole).
     */
    public static void drawUnderline(Graphics2D g2d, float x1, float x2, float y, float position, float thickness, Shape outline) {
        AffineTransform at = g2d.getTransform();
        double scale = at.getScaleX();
        Point2D a = at.transform(new Point2D.Double(x1, y), null), b = at.transform(new Point2D.Double(x2, y), null);
        double[] band = underlineBand(position * scale, thickness * scale);
        double top = Math.round(a.getY()) + band[0], height = band[1];
        List<double[]> gaps = outline != null ? inkCrossings(outline, at, top, top + height) : List.of();
        fillBand(g2d, a.getX(), b.getX(), top, height, gaps, CLEARANCE * scale);
    }

    /**
     * Draws a strikethrough from {@code x1} to {@code x2} centred {@code offset} (user space, negative above)
     * from the baseline {@code y}: under CoreText's rule whole device rows, at least one, the band rounded about
     * the centre; under Direct2D's the unsnapped rectangle of the thickness about the centre, from the rounded
     * baseline (the glyphs' row).
     */
    public static void drawStrikethrough(Graphics2D g2d, float x1, float x2, float y, float offset, float thickness) {
        AffineTransform at = g2d.getTransform();
        double scale = at.getScaleX();
        Point2D a = at.transform(new Point2D.Double(x1, y), null), b = at.transform(new Point2D.Double(x2, y), null);
        if (rule() == Rule.DIRECT_WRITE) {
            double height = thickness * scale;
            fillBand(g2d, a.getX(), b.getX(), Math.round(a.getY()) + offset * scale - height / 2, height, List.of(), 0);
            return;
        }
        int rows = Math.max(1, (int) Math.round(thickness * scale));
        int top = (int) Math.round(a.getY() + offset * scale - rows / 2.0);
        fillBand(g2d, a.getX(), b.getX(), top, rows, List.of(), 0);
    }

    /**
     * Fills the device band {@code [top, top + height)} from {@code x1} to {@code x2} (device), skipping the gaps
     * widened by {@code clearance} device pixels; the gaps' edges are fractional and antialiased, the band's ends
     * whole, its rows whole under CoreText's rule and fractional under Direct2D's.
     */
    static void fillBand(Graphics2D g2d, double x1, double x2, double top, double height, List<double[]> gaps, double clearance) {
        int left = (int) Math.round(x1), right = (int) Math.round(x2);
        if (right <= left || height <= 0) {
            return;
        }
        AffineTransform saved = g2d.getTransform();
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2d.setTransform(new AffineTransform());
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            double start = left;
            for (double[] gap : gaps) {
                double gx1 = gap[0] - clearance, gx2 = gap[1] + clearance;
                if (gx2 <= start || gx1 >= right) continue;
                if (gx1 > start) g2d.fill(new Rectangle2D.Double(start, top, gx1 - start, height));
                start = Math.max(start, gx2);
            }
            if (start < right) g2d.fill(new Rectangle2D.Double(start, top, right - start, height));
        } finally {
            g2d.setTransform(saved);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedAA == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : savedAA);
        }
    }

    /**
     * The device x ranges where the outline crosses the band {@code [yTop, yBottom)}: every segment of the
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
