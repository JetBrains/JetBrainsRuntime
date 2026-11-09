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
 * @summary Regression test for JBR-10654: translucent sources under Src, SrcIn and SrcOver on the
 *          Skia pipeline write what the software loops write.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 TranslucentSourceTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 TranslucentSourceTest skia
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Src and SrcIn with a translucent color and SrcOver with a constant alpha write what the
 * software loops write (not premultiplied values). A gradient between colors of different
 * alpha interpolates premultiplied, as a GPU pipeline does with the premultiplied stops it is
 * given; the software loops interpolate the raw ARGB values, so that region is checked against
 * a premultiplied reference computed here.
 */
public class TranslucentSourceTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 160;
    /** Blending in 8 bits on both sides: rounding. */
    private static final int TOLERANCE = 4;

    /** The light background and the dark band across it, so that the results depend on the destination. */
    private static final int LIGHT_BACKGROUND = 0xF0F0F0;
    private static final int DARK_BAND = 0x203040;
    private static final int BAND_TOP = 60;
    private static final int BAND_BOTTOM = 100;

    /** The four regions: three 40-wide columns and the 70-wide gradient, all from y = 20 to 140. */
    private static final int REGION_TOP = 20;
    private static final int REGION_HEIGHT = 120;
    private static final Rectangle SRC_REGION = new Rectangle(10, REGION_TOP, 40, REGION_HEIGHT);
    private static final Rectangle SRC_IN_REGION = new Rectangle(60, REGION_TOP, 40, REGION_HEIGHT);
    private static final Rectangle SRC_OVER_REGION = new Rectangle(110, REGION_TOP, 40, REGION_HEIGHT);
    private static final Rectangle GRADIENT_REGION = new Rectangle(160, REGION_TOP, 70, REGION_HEIGHT);

    private static final Color TRANSLUCENT_RED = new Color(200, 40, 40, 96);
    private static final Color GREEN = new Color(40, 160, 40);
    private static final float HALF_ALPHA = 0.5f;

    /** The gradient's axis, from the region's top left to its bottom right, and its stops: opaque to nearly clear. */
    private static final float GRADIENT_X0 = 160, GRADIENT_Y0 = 20, GRADIENT_X1 = 230, GRADIENT_Y1 = 140;
    private static final Color GRADIENT_START = new Color(255, 200, 0, 255);
    private static final Color GRADIENT_END = new Color(0, 120, 255, 32);

    /** One region of the sheet and what it shows. */
    private record Region(String name, Rectangle bounds) {
    }

    private static final List<Region> REGIONS = List.of(
            new Region("Src with a translucent color", SRC_REGION),
            new Region("SrcIn with a translucent color", SRC_IN_REGION),
            new Region("SrcOver with alpha 0.5", SRC_OVER_REGION),
            new Region("gradient between colors of different alpha", GRADIENT_REGION));

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, TranslucentSourceTest::paint);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, TranslucentSourceTest::paint);
        paintPremultipliedGradient(reference);
        StringBuilder failures = new StringBuilder();
        for (Region region : REGIONS) {
            PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, region.bounds(), TOLERANCE);
            System.out.println(region.name() + ": max " + difference.max() + " levels, "
                    + difference.beyond() + " pixels beyond " + TOLERANCE);
            if (difference.beyond() > 0) {
                failures.append(region.name()).append(" (").append(difference.beyond()).append(" pixels, max ")
                        .append(difference.max()).append(") ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Translucent sources differ from the reference by more than "
                    + TOLERANCE + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(new Color(LIGHT_BACKGROUND));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(new Color(DARK_BAND));
        graphics.fillRect(0, BAND_TOP, WIDTH, BAND_BOTTOM - BAND_TOP);
        // Src and SrcIn with a translucent color on an opaque destination
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(TRANSLUCENT_RED);
        fillRect(graphics, SRC_REGION);
        graphics.setComposite(AlphaComposite.SrcIn);
        fillRect(graphics, SRC_IN_REGION);
        // SrcOver with a constant alpha
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, HALF_ALPHA));
        graphics.setColor(GREEN);
        fillRect(graphics, SRC_OVER_REGION);
        // a gradient between colors of different alpha
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setPaint(new GradientPaint(GRADIENT_X0, GRADIENT_Y0, GRADIENT_START, GRADIENT_X1, GRADIENT_Y1, GRADIENT_END));
        fillRect(graphics, GRADIENT_REGION);
    }

    /** fillRect, the rect pipe (not the shape pipe a fill(Shape) would take). */
    private static void fillRect(Graphics2D graphics, Rectangle rectangle) {
        graphics.fillRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
    }

    /** Replaces the gradient region of the software reference by a premultiplied interpolation over the background. */
    private static void paintPremultipliedGradient(BufferedImage reference) {
        float dx = GRADIENT_X1 - GRADIENT_X0;
        float dy = GRADIENT_Y1 - GRADIENT_Y0;
        float axisLengthSquared = dx * dx + dy * dy;
        float[] startColor = premultiplied(GRADIENT_START);
        float[] endColor = premultiplied(GRADIENT_END);
        for (int y = GRADIENT_REGION.y; y < GRADIENT_REGION.y + GRADIENT_REGION.height; y++) {
            for (int x = GRADIENT_REGION.x; x < GRADIENT_REGION.x + GRADIENT_REGION.width; x++) {
                // the pixel center's position along the axis, clamped to the stops
                float position = ((x + 0.5f - GRADIENT_X0) * dx + (y + 0.5f - GRADIENT_Y0) * dy) / axisLengthSquared;
                position = Math.max(0f, Math.min(1f, position));
                int destination = y >= BAND_TOP && y < BAND_BOTTOM ? DARK_BAND : LIGHT_BACKGROUND;
                int[] composed = new int[3];
                float alpha = startColor[3] + (endColor[3] - startColor[3]) * position;
                for (int channel = 0; channel < 3; channel++) {
                    float source = startColor[channel] + (endColor[channel] - startColor[channel]) * position;   // premultiplied
                    float destinationChannel = ((destination >> (16 - 8 * channel)) & 0xff) / 255f;
                    composed[channel] = Math.round((source + destinationChannel * (1 - alpha)) * 255f);
                }
                reference.setRGB(x, y, (composed[0] << 16) | (composed[1] << 8) | composed[2]);
            }
        }
    }

    /** The color's red, green and blue multiplied by its alpha, and the alpha, in 0..1. */
    private static float[] premultiplied(Color color) {
        float alpha = color.getAlpha() / 255f;
        return new float[] { color.getRed() / 255f * alpha, color.getGreen() / 255f * alpha, color.getBlue() / 255f * alpha, alpha };
    }
}
