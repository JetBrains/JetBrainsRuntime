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

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

/**
 * @test
 * @summary A glyph vector's visual bounds (the union of the glyph outline
 *          bounds the strike reports) agree with the bounds of its outline
 *          for the logical fonts at several sizes and scales, on the
 *          platform's font scaler and on the Skia one. The editor measures
 *          line heights this way; the Skia scaler used to throw a
 *          ClassCastException from its outline bounds.
 * @key headful
 * @run main/othervm GlyphVectorBoundsTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia GlyphVectorBoundsTest
 */
public class GlyphVectorBoundsTest {
    public static void main(String[] args) {
        int checked = 0;
        for (String family : new String[] {Font.DIALOG, Font.SANS_SERIF, Font.MONOSPACED, Font.SERIF}) {
            for (int size : new int[] {9, 13, 24}) {
                for (double scale : new double[] {1, 2}) {
                    Font font = new Font(family, Font.PLAIN, size);
                    FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale),
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    GlyphVector gv = font.createGlyphVector(frc, "Quick fox jumps 1234567890 {}[]();:,.");
                    Rectangle2D visual = gv.getVisualBounds();
                    Rectangle2D outline = gv.getOutline().getBounds2D();
                    if (visual.isEmpty() || outline.isEmpty()) {
                        throw new RuntimeException(family + " " + size + "pt x" + scale + ": empty bounds " + visual + " / " + outline);
                    }
                    double tolerance = 1.0 / scale + 0.01;   // a device pixel: the strike's bounds are per glyph
                    if (Math.abs(visual.getX() - outline.getX()) > tolerance || Math.abs(visual.getY() - outline.getY()) > tolerance
                            || Math.abs(visual.getMaxX() - outline.getMaxX()) > tolerance
                            || Math.abs(visual.getMaxY() - outline.getMaxY()) > tolerance) {
                        throw new RuntimeException(family + " " + size + "pt x" + scale + ": visual bounds " + visual
                                                   + " vs outline bounds " + outline);
                    }
                    checked++;
                }
            }
        }
        System.out.println(checked + " glyph vectors checked");
        System.out.println("PASSED");
    }
}
