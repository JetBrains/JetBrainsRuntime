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
 * @summary Regression test for JBR-10654: a Skia VolatileImage drawn into itself renders as the
 *          software loops draw a BufferedImage into itself.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaSelfBlitTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaSelfBlitTest skia
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * drawImage with the image's own Graphics, source and destination regions apart: unscaled at an
 * offset, scaled, and after other drawing into the image in the same flush. The source is the
 * render target of the draw.
 */
public class SkiaSelfBlitTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 160;
    /** Opaque copies with nearest-neighbor scaling: the results must be identical. */
    private static final int TOLERANCE = 0;
    /** The source region: the top left quadrant of four colors. */
    private static final int SOURCE_WIDTH = 60;
    private static final int SOURCE_HEIGHT = 40;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D referenceGraphics = reference.createGraphics();
        paint(referenceGraphics, reference);
        referenceGraphics.dispose();

        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        BufferedImage actual = PipelineComparison.keepingContents(graphicsConfig, () -> {
            Graphics2D graphics = volatileImage.createGraphics();
            paint(graphics, volatileImage);
            graphics.dispose();
            return volatileImage.getSnapshot();
        }, volatileImage);

        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(difference.beyond() + " pixels differ from the software loops, max " + difference.max() + " levels");
        if (difference.beyond() > 0) {
            PipelineComparison.printDifferenceMap(reference, actual, TOLERANCE);
            throw new RuntimeException("an image drawn into itself differs from the software loops in "
                    + difference.beyond() + " pixels (max " + difference.max() + " levels)");
        }
        System.out.println("PASSED");
    }

    /** Paints the source quadrant into the image, then copies it from the image into the image itself. */
    private static void paint(Graphics2D graphics, Image self) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        // the source: the top left 60x40, four colors
        int halfWidth = SOURCE_WIDTH / 2;
        int halfHeight = SOURCE_HEIGHT / 2;
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, halfWidth, halfHeight);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(halfWidth, 0, halfWidth, halfHeight);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, halfHeight, halfWidth, halfHeight);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(halfWidth, halfHeight, halfWidth, halfHeight);
        // unscaled, at an offset
        graphics.drawImage(self, 80, 0, 80 + SOURCE_WIDTH, SOURCE_HEIGHT, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT, null);
        // scaled by 2 (nearest neighbor)
        graphics.drawImage(self, 0, 50, 2 * SOURCE_WIDTH, 50 + 2 * SOURCE_HEIGHT, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT, null);
        // drawn into, then drawn from, in one flush
        graphics.setColor(Color.MAGENTA);
        graphics.fillRect(10, 10, 10, 10);
        graphics.drawImage(self, 160, 50, 160 + SOURCE_WIDTH, 50 + SOURCE_HEIGHT, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT, null);
    }
}
