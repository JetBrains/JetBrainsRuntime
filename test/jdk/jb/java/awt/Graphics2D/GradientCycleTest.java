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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: cyclic gradients on the Skia pipeline match the software
 *          loops up to the seams of REPEAT gradients.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 GradientCycleTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 GradientCycleTest skia
 */

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * REFLECT gradients are continuous and must match the software loops up to dithering. A REPEAT
 * gradient jumps at the cycle boundary, and a GPU pipeline evaluates the boundary pixel in the
 * shader where the software loops accumulate in float, so a boundary row may wrap one pixel
 * earlier or later; such seams are allowed up to a budget that a wrong cycle length, tile mode
 * or interpolation exceeds by far.
 */
public class GradientCycleTest {

    /** The side of one cell of the sheet: one paint under one transform. */
    private static final int CELL = 64;
    /** Dithering and rounding between the loops and a GPU pipeline. */
    private static final int TOLERANCE = 8;
    /** REPEAT paints only: a tenth of the cell for the rows that wrap a pixel early or late. */
    private static final int SEAM_BUDGET = CELL * CELL / 10;

    /** A paint of the sheet and whether it repeats (gets the seam budget) or is continuous. */
    private record CyclicPaint(Paint paint, boolean repeats) {
    }

    private static final Color ORANGE = new Color(0xE05020);
    private static final Color BLUE = new Color(0x2060E0);
    private static final Color YELLOW = new Color(0xF0E040);
    /** Three stops, so that a wrong interpolation shows between the end colors. */
    private static final float[] THREE_STOPS = { 0f, 0.5f, 1f };
    private static final float[] TWO_STOPS = { 0f, 1f };

    /** The cell's transforms: identity, a half-pixel shift, a scale, and a rotation about its center. */
    private static final List<AffineTransform> TRANSFORMS = List.of(
            new AffineTransform(),
            AffineTransform.getTranslateInstance(0.5, 0.5),
            AffineTransform.getScaleInstance(1.5, 1.5),
            AffineTransform.getRotateInstance(0.3, CELL / 2.0, CELL / 2.0));

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        List<CyclicPaint> paints = paints();
        int width = CELL * paints.size();
        int height = CELL * TRANSFORMS.size();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(width, height, graphics -> paint(graphics, paints));
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, width, height, graphics -> paint(graphics, paints));
        StringBuilder failures = new StringBuilder();
        for (int row = 0; row < TRANSFORMS.size(); row++) {
            for (int column = 0; column < paints.size(); column++) {
                PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual,
                        new Rectangle(column * CELL, row * CELL, CELL, CELL), TOLERANCE);
                System.out.println("paint " + column + " transform " + row + ": max " + difference.max()
                        + " levels, " + difference.beyond() + " pixels beyond " + TOLERANCE);
                int allowed = paints.get(column).repeats() ? SEAM_BUDGET : 0;
                if (difference.beyond() > allowed) {
                    failures.append("paint ").append(column).append(" transform ").append(row)
                            .append(" (").append(difference.beyond()).append(" pixels, max ")
                            .append(difference.max()).append(") ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Cyclic gradients differ from the software loops by more than "
                    + TOLERANCE + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    /** Gradients with short cycles (a few cycles per cell), linear in three directions and radial. */
    private static List<CyclicPaint> paints() {
        Color[] threeColors = { ORANGE, YELLOW, BLUE };
        Color[] twoColors = { ORANGE, BLUE };
        return List.of(
            new CyclicPaint(new GradientPaint(16, 16, ORANGE, 32, 24, BLUE, true), false),
            new CyclicPaint(new LinearGradientPaint(20, 0, 36, 0, THREE_STOPS, threeColors, CycleMethod.REPEAT), true),
            new CyclicPaint(new LinearGradientPaint(20, 0, 36, 0, THREE_STOPS, threeColors, CycleMethod.REFLECT), false),
            new CyclicPaint(new LinearGradientPaint(0, 12, 0, 29, THREE_STOPS, threeColors, CycleMethod.REPEAT), true),
            new CyclicPaint(new LinearGradientPaint(5, 5, 21, 17, THREE_STOPS, threeColors, CycleMethod.REPEAT), true),
            new CyclicPaint(new RadialGradientPaint(32, 32, 10, TWO_STOPS, twoColors, CycleMethod.REPEAT), true),
            new CyclicPaint(new RadialGradientPaint(32, 32, 10, TWO_STOPS, twoColors, CycleMethod.REFLECT), false));
    }

    private static void paint(Graphics2D graphics, List<CyclicPaint> paints) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, CELL * paints.size(), CELL * TRANSFORMS.size());
        for (int row = 0; row < TRANSFORMS.size(); row++) {
            for (int column = 0; column < paints.size(); column++) {
                Graphics2D cellGraphics = (Graphics2D) graphics.create(column * CELL, row * CELL, CELL, CELL);
                try {
                    cellGraphics.transform(TRANSFORMS.get(row));
                    cellGraphics.setPaint(paints.get(column).paint());
                    // a fill well beyond the cell, so that the transform cannot leave a part of it uncovered
                    cellGraphics.fillRect(-CELL, -CELL, 3 * CELL, 3 * CELL);
                } finally {
                    cellGraphics.dispose();
                }
            }
        }
    }
}
