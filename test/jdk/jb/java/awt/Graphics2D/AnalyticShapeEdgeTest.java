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
 * @summary Regression test for JBR-10654: antialiased rects, round rectangles, pills and ellipses on
 *          the Skia pipeline have the edges and corner geometry of the software loops.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest skia 8 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest skia 2 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest skia 8 32
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A round rectangle whose arcs are wider than its sides is a pill: Java clamps each arc to its
 * own side (RoundRectIterator), where Skia's rounded rect scales both radii by one factor.
 * Marlin does not normalize the outline of an antialiased fill (only strokes are snapped), so
 * an edge on a pixel boundary is crisp and an edge at a fraction covers the outer pixel by that
 * fraction; a pipeline that snaps fill edges to pixel centers spills half a pixel over the right
 * and bottom edges of every rounded panel and tab. Fills get the first tolerance on the run
 * line; strokes the second, wider one: the analytic oval stroke and the parallelogram stroke
 * differ from Marlin's cubic ellipse and its stroker by a few levels at joins. With
 * shapes=marlin every fill is Marlin's mask, within a level or two of the loops where Skia's
 * analytic fills differ by 3 to 6: the fill tolerance of 2 on that line shows the route taken.
 */
public class AnalyticShapeEdgeTest {

    private static final int WIDTH = 140;
    private static final int HEIGHT = 100;

    /** The panel-like shapes drawn; a rect at a half pixel is left out (see {@link #main}). */
    private enum ShapeKind { RECT, ROUNDRECT, PILL, ELLIPSE, BIGELLIPSE }

    /** User-space scales: 1x, and 2x as on a HiDPI display. */
    private static final int[] SCALES = { 1, 2 };
    /** The shape's edge on a pixel boundary, and at a half pixel. */
    private static final double[] EDGE_FRACTIONS = { 0, 0.5 };

    /** The shapes' top left corner in user space, before the edge fraction. */
    private static final double ORIGIN = 4;
    /** The size of the panel-like shapes: wide and low, like a tab. */
    private static final double PANEL_WIDTH = 41;
    private static final double PANEL_HEIGHT = 20;
    /** The big ellipse: tall enough that its arcs are not nearly straight. */
    private static final double BIG_ELLIPSE_WIDTH = 56;
    private static final double BIG_ELLIPSE_HEIGHT = 36;
    /** The round rectangle's arc diameter, smaller than its sides. */
    private static final double ROUNDRECT_ARC = 8;
    /** The pill's arc diameter: wider than the shape's height, so Java clamps it (RoundRectIterator). */
    private static final double PILL_ARC = 48;
    /** A 1 px stroke lies on pixel centers when its outline is inset by half a pixel. */
    private static final double HALF_PIXEL = 0.5;

    private static final Color BACKGROUND = new Color(0x191A1C);
    private static final Color FILL = new Color(0x233558);
    private static final Color OUTLINE = new Color(0x2E4D89);

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int fillTolerance = Integer.parseInt(args[1]);
        int strokeTolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        StringBuilder failures = new StringBuilder();
        for (int scale : SCALES) {
            for (ShapeKind shape : ShapeKind.values()) {
                for (double fraction : EDGE_FRACTIONS) {
                    if (shape == ShapeKind.RECT && fraction != 0) {
                        // a rect at a half pixel is the parallelogram pipe's business (its corner pixels are 15 levels off)
                        continue;
                    }
                    for (boolean stroke : List.of(false, true)) {
                        int tolerance = stroke ? strokeTolerance : fillTolerance;
                        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT,
                                graphics -> paint(graphics, scale, shape, fraction, stroke));
                        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT,
                                graphics -> paint(graphics, scale, shape, fraction, stroke));
                        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
                        String what = shape.name().toLowerCase() + " at " + scale + "x, edge fraction " + fraction
                                      + (stroke ? ", stroked" : ", filled");
                        System.out.println(what + ": max " + difference.max() + " levels, "
                                + difference.beyond() + " pixels beyond " + tolerance);
                        if (difference.beyond() > 0) {
                            failures.append(what).append(" (max ").append(difference.max()).append(") ");
                        }
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Antialiased shape edges differ from the software loops beyond "
                    + fillTolerance + " (fills) / " + strokeTolerance + " (strokes) levels: " + failures);
        }
        System.out.println("PASSED");
    }

    /** A panel-like shape: integer user-space bounds (plus a fraction), filled, and optionally outlined with a 1 px stroke. */
    private static void paint(Graphics2D graphics, int scale, ShapeKind shape, double fraction, boolean stroke) {
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.scale(scale, scale);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double x = ORIGIN + fraction;
        double y = ORIGIN + fraction;
        double width = shape == ShapeKind.BIGELLIPSE ? BIG_ELLIPSE_WIDTH : PANEL_WIDTH;
        double height = shape == ShapeKind.BIGELLIPSE ? BIG_ELLIPSE_HEIGHT : PANEL_HEIGHT;
        graphics.setColor(FILL);
        graphics.fill(shape(shape, x, y, width, height));
        if (stroke) {
            graphics.setColor(OUTLINE);
            graphics.setStroke(new BasicStroke(1f));
            graphics.draw(shape(shape, x + HALF_PIXEL, y + HALF_PIXEL, width - 1, height - 1));
        }
    }

    private static Shape shape(ShapeKind shape, double x, double y, double width, double height) {
        return switch (shape) {
            case RECT -> new Rectangle2D.Double(x, y, width, height);
            case ROUNDRECT -> new RoundRectangle2D.Double(x, y, width, height, ROUNDRECT_ARC, ROUNDRECT_ARC);
            case PILL -> new RoundRectangle2D.Double(x, y, width, height, PILL_ARC, PILL_ARC);
            case ELLIPSE, BIGELLIPSE -> new Ellipse2D.Double(x, y, width, height);
        };
    }
}
