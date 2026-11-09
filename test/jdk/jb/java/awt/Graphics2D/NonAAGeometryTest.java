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
 * @summary Regression test for JBR-10654: non-antialiased geometry on the Skia pipeline is pixel
 *          exact and agrees with the software loops up to an edge budget.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 NonAAGeometryTest skia 160
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 NonAAGeometryTest skia 160
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 NonAAGeometryTest skia 160
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Every pixel is either the background or the fill color, and the pixels chosen agree with
 * the software loops up to the edge pixels where the two rasterization rules differ; the second
 * run-line argument is the budget of such pixels per cell (measured: 127 on the rotated stroked
 * rectangle, 50 on the sheared one, below 30 elsewhere). The sheet's last column is an
 * antialiased general path rasterized by Skia, not checked: with multisampling, Graphite
 * multisamples every draw of a pass as soon as one draw in it needs it and would give the edges
 * of rotated fills, lines and fractional blits four coverage levels.
 */
public class NonAAGeometryTest {

    /** The side of one cell of the sheet: one shape under one transform. */
    private static final int CELL = 64;
    private static final int BACKGROUND = 0xFFFFFF;
    private static final int FILL = 0x2040C0;
    /** Edge pixels per cell where a pipeline's pixel-center rule and the software loops may pick differently. */
    private static final int DEFAULT_EDGE_BUDGET = 200;
    /** The non-antialiased shapes checked, one column each; the antialiased star is the extra column. */
    private static final int SHAPES = 6;
    /** The side of the opaque image blitted in the last checked column. */
    private static final int SOURCE_SIZE = 24;
    /** The star's points alternate between the outer and the inner radius, about the cell's center. */
    private static final int STAR_POINTS = 10;
    private static final double STAR_OUTER_RADIUS = 26;
    private static final double STAR_INNER_RADIUS = 11;

    /** The cell's transforms: identity, a half-pixel shift, a scale, a rotation about its center, a shear. */
    private static final List<AffineTransform> TRANSFORMS = List.of(
            new AffineTransform(),
            AffineTransform.getTranslateInstance(0.5, 0.5),
            AffineTransform.getScaleInstance(1.5, 1.5),
            AffineTransform.getRotateInstance(0.3, CELL / 2.0, CELL / 2.0),
            AffineTransform.getShearInstance(0.3, 0));

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int edgeBudget = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_EDGE_BUDGET;
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        int width = CELL * (SHAPES + 1);
        int height = CELL * TRANSFORMS.size();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(width, height, NonAAGeometryTest::paint);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, width, height, NonAAGeometryTest::paint);
        StringBuilder failures = new StringBuilder();
        for (int row = 0; row < TRANSFORMS.size(); row++) {
            for (int column = 0; column < SHAPES; column++) {
                int partial = 0;
                int differing = 0;
                for (int y = row * CELL; y < (row + 1) * CELL; y++) {
                    for (int x = column * CELL; x < (column + 1) * CELL; x++) {
                        int actualPixel = actual.getRGB(x, y) & 0xffffff;
                        if (actualPixel != BACKGROUND && actualPixel != FILL) {
                            partial++;
                        }
                        if (actualPixel != (reference.getRGB(x, y) & 0xffffff)) {
                            differing++;
                        }
                    }
                }
                System.out.println("shape " + column + " transform " + row + ": " + partial
                        + " pixels of a third color, " + differing + " pixels differ from software");
                if (partial > 0 || differing > edgeBudget) {
                    failures.append("shape ").append(column).append(" transform ").append(row)
                            .append(" (").append(partial).append(" partial, ").append(differing)
                            .append(" differing) ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Non-antialiased geometry is not pixel exact (edge budget "
                    + edgeBudget + " pixels per cell): " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(new Color(BACKGROUND));
        graphics.fillRect(0, 0, CELL * (SHAPES + 1), CELL * TRANSFORMS.size());
        BufferedImage source = new BufferedImage(SOURCE_SIZE, SOURCE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D sourceGraphics = source.createGraphics();
        sourceGraphics.setColor(new Color(FILL));
        sourceGraphics.fillRect(0, 0, SOURCE_SIZE, SOURCE_SIZE);
        sourceGraphics.dispose();
        for (int row = 0; row < TRANSFORMS.size(); row++) {
            // an antialiased general path in the same pass, so that a multisampled pass is multisampled
            Graphics2D antialiasedGraphics = (Graphics2D) graphics.create(SHAPES * CELL, row * CELL, CELL, CELL);
            try {
                antialiasedGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                antialiasedGraphics.setColor(new Color(FILL));
                antialiasedGraphics.fill(star());
            } finally {
                antialiasedGraphics.dispose();
            }
            for (int column = 0; column < SHAPES; column++) {
                Graphics2D cellGraphics = (Graphics2D) graphics.create(column * CELL, row * CELL, CELL, CELL);
                try {
                    cellGraphics.transform(TRANSFORMS.get(row));
                    cellGraphics.setColor(new Color(FILL));
                    switch (column) {
                        case 0 -> cellGraphics.fillRect(12, 12, 30, 24);
                        case 1 -> cellGraphics.drawRect(12, 12, 30, 24);
                        case 2 -> cellGraphics.drawLine(8, 10, 50, 40);
                        case 3 -> {
                            cellGraphics.setStroke(new BasicStroke(5f));
                            cellGraphics.drawLine(8, 40, 50, 10);
                        }
                        case 4 -> cellGraphics.fillPolygon(new int[] { 10, 50, 30 }, new int[] { 48, 44, 10 }, 3);
                        case 5 -> cellGraphics.drawImage(source, 14, 14, null);
                        default -> throw new IllegalStateException("shape " + column);
                    }
                } finally {
                    cellGraphics.dispose();
                }
            }
        }
    }

    /** A five-pointed star about the cell's center. */
    private static Path2D star() {
        Path2D star = new Path2D.Float();
        double center = CELL / 2.0;
        for (int point = 0; point < STAR_POINTS; point++) {
            double radius = point % 2 == 0 ? STAR_OUTER_RADIUS : STAR_INNER_RADIUS;
            double angle = point * 2 * Math.PI / STAR_POINTS;
            double x = center + radius * Math.sin(angle);
            double y = center - radius * Math.cos(angle);
            if (point == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        return star;
    }
}
