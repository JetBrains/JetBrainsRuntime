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
 * {@link sun.java2d.skia.SKEnvironment#textDecorations()}): a line occupies whole device pixel rows — its top
 * row is the rounded top of the stroked line, its thickness at least one pixel — and an underline leaves a
 * gap wherever glyph ink crosses the line, with a clearance of one device pixel on each side (CoreText skips
 * the descenders; Windows' DirectWrite does not, so the gaps are macOS only). The same rule, in the same
 * arithmetic, draws the decorations of Skia's paragraphs ({@code SKBackend.cpp}, the paragraph visitor), so
 * Java's TextLayout and SkParagraph render an underlined string alike. Only translations and uniform scales
 * are snapped; under a rotation or shear the stroked line is drawn as before.
 */
public final class TextDecorations {
    private TextDecorations() {}

    /** The horizontal clearance around ink crossing an underline, in device pixels. */
    static final double CLEARANCE = 1.0;

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

    /** The bottom of the underline's band in user space at scale 1, for bounds (the rows at other scales lie within it). */
    public static float underlineBottom(float[] fontMetrics) {
        int[] band = underlineBand(fontMetrics[0], fontMetrics[1]);
        return band[0] + band[1];
    }

    /**
     * The device rows of an underline whose metrics (device pixels) put the top of the stroke {@code position}
     * below the baseline with {@code thickness}: CoreText's placement, measured over 34 fonts and sizes at 1x
     * (2026-11-02): whole rows, the thickness rounded up, the row holding the stroke's top for one row and
     * the band growing upwards from there for more, never nearer the baseline than its own height (the row
     * under the glyph bottoms stays clear). Returns {offset of the top row from the baseline y, rows}: the
     * baseline y is the glyphs' bottom edge, the row above it holds their bottoms.
     */
    public static int[] underlineBand(double position, double thickness) {
        int rows = Math.max(1, (int) Math.ceil(thickness - 1e-3));
        int top = Math.max(rows, (int) Math.floor(position + 0.5 - rows / 2.0));
        return new int[] {top, rows};
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
        int[] band = underlineBand(position * scale, thickness * scale);
        int top = (int) Math.round(a.getY()) + band[0], rows = band[1];
        List<double[]> gaps = outline != null ? inkCrossings(outline, at, top, top + rows) : List.of();
        fillBand(g2d, a.getX(), b.getX(), top, rows, gaps);
    }

    /**
     * Draws a strikethrough from {@code x1} to {@code x2} centred {@code offset} (user space, negative above)
     * from the baseline {@code y}: whole device rows, at least one, the band rounded about the centre.
     */
    public static void drawStrikethrough(Graphics2D g2d, float x1, float x2, float y, float offset, float thickness) {
        AffineTransform at = g2d.getTransform();
        double scale = at.getScaleX();
        Point2D a = at.transform(new Point2D.Double(x1, y), null), b = at.transform(new Point2D.Double(x2, y), null);
        int rows = Math.max(1, (int) Math.round(thickness * scale));
        int top = (int) Math.round(a.getY() + offset * scale - rows / 2.0);
        fillBand(g2d, a.getX(), b.getX(), top, rows, List.of());
    }

    /**
     * Fills the device rows {@code [top, top + rows)} from {@code x1} to {@code x2} (device), skipping the gaps
     * widened by the clearance; the gaps' edges are fractional and antialiased, the band's rows and ends whole.
     */
    static void fillBand(Graphics2D g2d, double x1, double x2, int top, int rows, List<double[]> gaps) {
        int left = (int) Math.round(x1), right = (int) Math.round(x2);
        if (right <= left) {
            return;
        }
        AffineTransform saved = g2d.getTransform();
        Object savedAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2d.setTransform(new AffineTransform());
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            double start = left;
            for (double[] gap : gaps) {
                double gx1 = gap[0] - CLEARANCE, gx2 = gap[1] + CLEARANCE;
                if (gx2 <= start || gx1 >= right) continue;
                if (gx1 > start) g2d.fill(new Rectangle2D.Double(start, top, gx1 - start, rows));
                start = Math.max(start, gx2);
            }
            if (start < right) g2d.fill(new Rectangle2D.Double(start, top, right - start, rows));
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
