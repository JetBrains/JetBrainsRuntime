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
 * @summary Regression test for JBR-10654: antialiased ellipses and round rectangles with huge or
 *          non-finite coordinates render on a Skia surface as the software loops render them.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaHugeShapeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 SkiaHugeShapeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaHugeShapeTest skia
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Marlin clips shapes in double precision, where Skia's analytic shapes receive floats (beyond
 * the float range the shape is gone, at 1e8 its edges are off by pixels): ellipses whose edge
 * passes through the image at 1e8, a round rectangle reaching out to -1e39, strokes of them, and
 * shapes with NaN bounds (nothing drawn). Shapes reaching beyond 2^16 device pixels, where a
 * float's spacing grows past 1/128 pixel, are left to Marlin (SKAnalyticShapes.MAX_DEVICE_COORDINATE):
 * an ellipse whose extent ends just over 2^16 matches the loops as Marlin's masks do (on the
 * analytic route it sits up to 7 levels off them); one ending just under 2^16 stays analytic and
 * matches them as the analytic shapes do.
 */
public class SkiaHugeShapeTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 160;
    /** Marlin's masks on both sides once the shapes leave the analytic route: the mask fill's rounding only. */
    private static final int TOLERANCE = 2;
    /** Skia's analytic fill against Marlin's coverage (AnalyticShapeEdgeTest's fill tolerance). */
    private static final int ANALYTIC_TOLERANCE = 8;
    /** SKAnalyticShapes.MAX_DEVICE_COORDINATE: beyond it a shape goes to Marlin. */
    private static final double LIMIT = 1 << 16;
    /** Half a pixel either side of the limit: just under stays analytic, just over goes to Marlin. */
    private static final double HALF_PIXEL = 0.5;

    /** A shape this large has float-precision edges off by pixels on the analytic route. */
    private static final double HUGE = 1e8;
    /** Beyond the float range (about 3.4e38): the analytic shape would be gone. */
    private static final double BEYOND_FLOAT = 1e39;

    /** The x of the vertical edge the huge filled ellipse puts through the image, and the stroked one's. */
    private static final double FILLED_EDGE_X = 40.5;
    private static final double STROKED_EDGE_X = 100.5;
    /** The y of the center of the huge ellipses. */
    private static final double ELLIPSE_CENTER_Y = 40;
    /** The y of the center of the ellipse reaching out to the limit. */
    private static final double REACHING_CENTER_Y = 80;

    private static final Color BLUE = new Color(0x203080);
    private static final Color GREEN = new Color(0x80C040);
    private static final Color ORANGE = new Color(0xC04020);

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        StringBuilder failures = new StringBuilder();
        check(graphicsConfig, "huge and non-finite shapes", SkiaHugeShapeTest::paint, TOLERANCE, failures);
        check(graphicsConfig, "an ellipse ending just under 2^16",
              graphics -> paintEllipseReaching(graphics, LIMIT - HALF_PIXEL), ANALYTIC_TOLERANCE, failures);
        check(graphicsConfig, "an ellipse ending just over 2^16",
              graphics -> paintEllipseReaching(graphics, LIMIT + HALF_PIXEL), TOLERANCE, failures);
        if (failures.length() > 0) {
            throw new RuntimeException("huge shapes differ from the software loops: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void check(GraphicsConfiguration graphicsConfig, String what, Consumer<Graphics2D> painter, int tolerance,
                              StringBuilder failures) {
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, painter);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, painter);
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
        System.out.println(what + ": " + difference.beyond() + " pixels differ from the software loops by more than " + tolerance
                + " levels, max " + difference.max() + ", missing ink " + difference.missingInk());
        if (difference.beyond() > 0) {
            PipelineComparison.printDifferenceMap(reference, actual, tolerance);
            failures.append(what).append(" (").append(difference.beyond()).append(" pixels, max ").append(difference.max())
                    .append(" levels); ");
        }
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(BLUE);
        // an ellipse 2e8 wide whose leftmost point is (40.5, 40): a vertical edge at x = 40.5 here
        graphics.fill(new Ellipse2D.Double(FILLED_EDGE_X, ELLIPSE_CENTER_Y - HUGE, 2 * HUGE, 2 * HUGE));
        // the same as a stroke, its edge at x = 100.5
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(new Ellipse2D.Double(STROKED_EDGE_X, ELLIPSE_CENTER_Y - HUGE, 2 * HUGE, 2 * HUGE));
        // a round rectangle from -1e39 to x = 30.25: beyond the float range
        graphics.setColor(GREEN);
        graphics.fill(new RoundRectangle2D.Double(-BEYOND_FLOAT, 90.5, BEYOND_FLOAT + 30.25, 30, 10, 10));
        // a round rectangle 2e8 tall whose top edge is y = 130.25, stroked
        graphics.setColor(ORANGE);
        graphics.draw(new RoundRectangle2D.Double(150.5, 130.25, 60, 2 * HUGE, 16, 16));
        // NaN bounds: nothing
        graphics.setColor(Color.BLACK);
        graphics.fill(new Ellipse2D.Double(Double.NaN, 10, 50, 20));
        graphics.draw(new Ellipse2D.Double(10, 10, Double.NaN, 20));
        graphics.fill(new RoundRectangle2D.Double(10, Double.NaN, 50, 20, 6, 6));
        graphics.draw(new RoundRectangle2D.Double(10, 10, 50, 20, Double.NaN, 6));
    }

    /**
     * A filled circle whose leftmost point is (40.5, 80), a nearly vertical edge through the
     * image, and whose bounds reach out to x = extent (their largest device coordinate).
     */
    private static void paintEllipseReaching(Graphics2D graphics, double extent) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(BLUE);
        double diameter = extent - FILLED_EDGE_X;
        graphics.fill(new Ellipse2D.Double(FILLED_EDGE_X, REACHING_CENTER_Y - diameter / 2, diameter, diameter));
    }
}
