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
 * @summary Regression test for JBR-10654: fills, lines and image blits in XOR mode on the Skia
 *          pipeline are XORed with the destination as the software loops do.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 XORModeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 XORModeTest skia
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;

/**
 * dst ^ ((color ^ xorColor) * coverage) for colors, dst ^ pixel ^ xorColor for images. Text
 * under XOR is left out: the software loops XOR it with binary coverage, a GPU pipeline with its
 * antialiased coverage.
 */
public class XORModeTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 120;
    /** XOR is exact on both sides: the results must be identical. */
    private static final int TOLERANCE = 0;
    /** The translucent source image, and the opaque one. */
    private static final int SOURCE_SIZE = 40;
    private static final int OPAQUE_SIZE = 30;
    private static final Color XOR_COLOR = new Color(0x00FF80);
    private static final Color DRAW_COLOR = new Color(0xC03030);

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, XORModeTest::paint);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, XORModeTest::paint);
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(difference.beyond() + " pixels differ from the software loops, max "
                + difference.max() + " levels");
        if (difference.beyond() > 0) {
            throw new RuntimeException("XOR rendering differs from the software loops: "
                    + difference.beyond() + " pixels, max " + difference.max() + " levels");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics) {
        // a background with a dark band, so that the XOR result depends on the destination
        graphics.setColor(new Color(0xF0F0F0));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(new Color(0x203040));
        graphics.fillRect(0, 40, WIDTH, 40);
        // a translucent source image: an opaque square with a half-transparent disc
        BufferedImage source = new BufferedImage(SOURCE_SIZE, SOURCE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sourceGraphics = source.createGraphics();
        sourceGraphics.setColor(new Color(255, 200, 0));
        sourceGraphics.fillRect(0, 0, SOURCE_SIZE, SOURCE_SIZE);
        sourceGraphics.setColor(new Color(0, 120, 255, 128));
        sourceGraphics.fillOval(8, 8, 24, 24);
        sourceGraphics.dispose();

        graphics.setXORMode(XOR_COLOR);
        graphics.setColor(DRAW_COLOR);
        graphics.fillRect(10, 20, 50, 80);
        graphics.fillOval(70, 20, 50, 80);
        graphics.drawRect(130, 20, 50, 80);
        graphics.drawLine(10, 10, 190, 110);
        graphics.setStroke(new BasicStroke(5f));
        graphics.drawLine(190, 10, 10, 110);
        graphics.setStroke(new BasicStroke(1f));
        graphics.fillPolygon(new int[] { 20, 60, 40 }, new int[] { 100, 100, 60 }, 3);
        graphics.drawImage(source, 140, 70, null);
        // an opaque source image: dst ^ pixel ^ xorColor
        BufferedImage opaque = new BufferedImage(OPAQUE_SIZE, OPAQUE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D opaqueGraphics = opaque.createGraphics();
        opaqueGraphics.setColor(new Color(0x4080C0));
        opaqueGraphics.fillRect(0, 0, OPAQUE_SIZE, OPAQUE_SIZE);
        opaqueGraphics.dispose();
        graphics.drawImage(opaque, 100, 2, null);
        // XOR applied twice restores the destination
        graphics.fillRect(150, 100, 40, 15);
        graphics.fillRect(150, 100, 40, 15);
        graphics.setPaintMode();
    }
}
