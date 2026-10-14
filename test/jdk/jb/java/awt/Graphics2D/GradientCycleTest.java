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
 * @summary Cyclic gradients on a GPU pipeline against the software loops.
 *          REFLECT gradients are continuous and must match up to dithering.
 *          A REPEAT gradient jumps at the cycle boundary, and the GPU
 *          pipelines (Metal as well as Skia) evaluate the boundary pixel in
 *          the shader where the software loops accumulate in float, so a
 *          boundary row may wrap one pixel earlier or later; such seams are
 *          allowed up to a budget that a wrong cycle length, tile mode or
 *          interpolation exceeds by far.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 GradientCycleTest skia
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 GradientCycleTest metal
 */

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

public class GradientCycleTest {

    private static final int CELL = 64;
    private static final int TOLERANCE = 8;
    private static final int SEAM_BUDGET = CELL * CELL / 10;   // REPEAT paints only
    private static final boolean[] REPEAT = { false, true, false, true, true, true, false };

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Paint[] paints = paints();
        AffineTransform[] transforms = {
            new AffineTransform(),
            AffineTransform.getTranslateInstance(0.5, 0.5),
            AffineTransform.getScaleInstance(1.5, 1.5),
            AffineTransform.getRotateInstance(0.3, CELL / 2.0, CELL / 2.0),
        };
        int w = CELL * paints.length;
        int h = CELL * transforms.length;
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        BufferedImage reference = PipelineComparison.softwareReference(w, h, g -> paint(g, paints, transforms));
        BufferedImage actual = PipelineComparison.rendered(gc, w, h, g -> paint(g, paints, transforms));
        StringBuilder failures = new StringBuilder();
        for (int row = 0; row < transforms.length; row++) {
            for (int col = 0; col < paints.length; col++) {
                PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual,
                        new Rectangle(col * CELL, row * CELL, CELL, CELL), TOLERANCE);
                System.out.println("paint " + col + " transform " + row + ": max " + difference.max()
                        + " levels, " + difference.beyond() + " pixels beyond " + TOLERANCE);
                if (difference.beyond() > (REPEAT[col] ? SEAM_BUDGET : 0)) {
                    failures.append("paint ").append(col).append(" transform ").append(row)
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

    private static Paint[] paints() {
        Color a = new Color(0xE05020);
        Color b = new Color(0x2060E0);
        Color c = new Color(0xF0E040);
        float[] stops = { 0f, 0.5f, 1f };
        return new Paint[] {
            new GradientPaint(16, 16, a, 32, 24, b, true),
            new LinearGradientPaint(20, 0, 36, 0, stops, new Color[] { a, c, b }, CycleMethod.REPEAT),
            new LinearGradientPaint(20, 0, 36, 0, stops, new Color[] { a, c, b }, CycleMethod.REFLECT),
            new LinearGradientPaint(0, 12, 0, 29, stops, new Color[] { a, c, b }, CycleMethod.REPEAT),
            new LinearGradientPaint(5, 5, 21, 17, stops, new Color[] { a, c, b }, CycleMethod.REPEAT),
            new RadialGradientPaint(32, 32, 10, new float[] { 0f, 1f }, new Color[] { a, b }, CycleMethod.REPEAT),
            new RadialGradientPaint(32, 32, 10, new float[] { 0f, 1f }, new Color[] { a, b }, CycleMethod.REFLECT),
        };
    }

    private static void paint(Graphics2D g, Paint[] paints, AffineTransform[] transforms) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CELL * paints.length, CELL * transforms.length);
        for (int row = 0; row < transforms.length; row++) {
            for (int col = 0; col < paints.length; col++) {
                Graphics2D cg = (Graphics2D) g.create(col * CELL, row * CELL, CELL, CELL);
                try {
                    cg.transform(transforms[row]);
                    cg.setPaint(paints[col]);
                    cg.fillRect(-CELL, -CELL, 3 * CELL, 3 * CELL);
                } finally {
                    cg.dispose();
                }
            }
        }
    }
}
