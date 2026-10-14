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
 * @summary Non-antialiased geometry on a GPU pipeline is pixel exact:
 *          every pixel is either the background or the fill colour, with or
 *          without multisampling (Skia), and the pixels chosen agree with the
 *          software loops up to the edge pixels where the two rasterization
 *          rules differ; the second run-line argument is the budget of such
 *          pixels per cell (measured: 127 on the rotated stroked rectangle,
 *          50 on the sheared one, below 30 elsewhere, the same on Skia and
 *          Metal). With multisampling, Graphite multisamples every
 *          draw of a pass as soon as one draw in it needs it (an antialiased
 *          general path rasterized by Skia, the last column of the sheet) and
 *          would give the edges of rotated fills, lines and fractional blits
 *          four coverage levels.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.msaa=1 -Dsun.java2d.uiScale=1 NonAAGeometryTest skia 160
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.msaa=4 -Dsun.java2d.skia.shapes=skia -Dsun.java2d.uiScale=1 NonAAGeometryTest skia 160
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 NonAAGeometryTest metal 160
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

public class NonAAGeometryTest {

    private static final int CELL = 64;
    private static final int BACKGROUND = 0xFFFFFF;
    private static final int FILL = 0x2040C0;
    // edge pixels per cell where a pipeline's pixel-centre rule and the software loops may pick differently
    private static final int DEFAULT_EDGE_BUDGET = 200;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int edgeBudget = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_EDGE_BUDGET;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        AffineTransform[] transforms = {
            new AffineTransform(),
            AffineTransform.getTranslateInstance(0.5, 0.5),
            AffineTransform.getScaleInstance(1.5, 1.5),
            AffineTransform.getRotateInstance(0.3, CELL / 2.0, CELL / 2.0),
            AffineTransform.getShearInstance(0.3, 0),
        };
        int shapes = 6;   // plus the antialiased column that is not checked
        int w = CELL * (shapes + 1);
        int h = CELL * transforms.length;
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        BufferedImage reference = PipelineComparison.softwareReference(w, h, g -> paint(g, transforms, shapes));
        BufferedImage actual = PipelineComparison.rendered(gc, w, h, g -> paint(g, transforms, shapes));
        StringBuilder failures = new StringBuilder();
        for (int row = 0; row < transforms.length; row++) {
            for (int col = 0; col < shapes; col++) {
                int partial = 0;
                int differing = 0;
                for (int y = row * CELL; y < (row + 1) * CELL; y++) {
                    for (int x = col * CELL; x < (col + 1) * CELL; x++) {
                        int q = actual.getRGB(x, y) & 0xffffff;
                        if (q != BACKGROUND && q != FILL) {
                            partial++;
                        }
                        if (q != (reference.getRGB(x, y) & 0xffffff)) {
                            differing++;
                        }
                    }
                }
                System.out.println("shape " + col + " transform " + row + ": " + partial
                        + " pixels of a third colour, " + differing + " pixels differ from software");
                if (partial > 0 || differing > edgeBudget) {
                    failures.append("shape ").append(col).append(" transform ").append(row)
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

    private static void paint(Graphics2D g, AffineTransform[] transforms, int shapes) {
        g.setColor(new Color(BACKGROUND));
        g.fillRect(0, 0, CELL * (shapes + 1), CELL * transforms.length);
        BufferedImage source = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = source.createGraphics();
        sg.setColor(new Color(FILL));
        sg.fillRect(0, 0, 24, 24);
        sg.dispose();
        for (int row = 0; row < transforms.length; row++) {
            // an antialiased general path in the same pass, so that a multisampled pass is multisampled
            Graphics2D ag = (Graphics2D) g.create(shapes * CELL, row * CELL, CELL, CELL);
            try {
                ag.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ag.setColor(new Color(FILL));
                Path2D star = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double r = i % 2 == 0 ? 26 : 11;
                    double a = i * Math.PI / 5;
                    if (i == 0) {
                        star.moveTo(32 + r * Math.sin(a), 32 - r * Math.cos(a));
                    } else {
                        star.lineTo(32 + r * Math.sin(a), 32 - r * Math.cos(a));
                    }
                }
                star.closePath();
                ag.fill(star);
            } finally {
                ag.dispose();
            }
            for (int col = 0; col < shapes; col++) {
                Graphics2D cg = (Graphics2D) g.create(col * CELL, row * CELL, CELL, CELL);
                try {
                    cg.transform(transforms[row]);
                    cg.setColor(new Color(FILL));
                    switch (col) {
                        case 0 -> cg.fillRect(12, 12, 30, 24);
                        case 1 -> cg.drawRect(12, 12, 30, 24);
                        case 2 -> cg.drawLine(8, 10, 50, 40);
                        case 3 -> {
                            cg.setStroke(new BasicStroke(5f));
                            cg.drawLine(8, 40, 50, 10);
                        }
                        case 4 -> cg.fillPolygon(new int[] { 10, 50, 30 }, new int[] { 48, 44, 10 }, 3);
                        case 5 -> cg.drawImage(source, 14, 14, null);
                        default -> throw new IllegalStateException();
                    }
                } finally {
                    cg.dispose();
                }
            }
        }
    }
}
