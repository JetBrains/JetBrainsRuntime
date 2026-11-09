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
 * @summary Regression test for JBR-10654: a TexturePaint fill on the Skia pipeline samples what its
 *          new image holds, each texel where the software loops put it.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaTexturePaintTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 SkiaTexturePaintTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaTexturePaintTest skia
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The image of a new TexturePaint is cached as a Skia texture when the paint is validated, and
 * its upload is recorded in the same flush that samples it: the texture must be sampled through
 * the surface's linked image (as blits do), not a bare view of the texture, or Graphite does not
 * order the upload before the fill and the fill comes out black
 * (java/awt/Graphics2D/FillTexturePaint). Opaque and translucent images, Src and SrcOver, an
 * offset anchor, filled and antialiased shapes, a scaled anchor with bilinear filtering; each
 * texel where the loops put it (Java samples texel floor(u) at the pixel's integer coordinates).
 */
public class SkiaTexturePaintTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 120;
    /** Sampling a texture against the loops' texel lookup: rounding of translucent colors and the bilinear filter. */
    private static final int TOLERANCE = 4;
    private static final int TILE = PipelineComparison.TILE_SIZE;
    /** The four fills: 60 by 100, 70 apart. */
    private static final int FILL_WIDTH = 60;
    private static final int FILL_HEIGHT = 100;
    private static final int FILL_PITCH = 70;
    private static final int FIRST_X = 10;
    private static final int FILL_Y = 10;
    /** The translucent tile's alpha: half. */
    private static final int HALF_ALPHA = 128;
    private static final int OPAQUE_ALPHA = 255;

    /** One fill of the sheet: where it is, what it shows, and how many pixels of its rim may differ. */
    private record Fill(String name, Rectangle region, int allowedBeyond) {
        Fill(String name, int index, boolean antialiasedRim) {
            this(name, new Rectangle(FIRST_X + index * FILL_PITCH, FILL_Y, FILL_WIDTH, FILL_HEIGHT),
                 // the oval's antialiased rim differs between rasterizers; its interior must not
                 antialiasedRim ? 2 * (FILL_WIDTH + FILL_HEIGHT) : 0);
        }
    }

    private static final List<Fill> FILLS = List.of(
            new Fill("opaque image, Src", 0, false),
            new Fill("translucent image, SrcOver, offset anchor", 1, false),
            new Fill("opaque image, antialiased oval", 2, true),
            new Fill("opaque image, scaled 2x, bilinear", 3, false));

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, SkiaTexturePaintTest::paint);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, SkiaTexturePaintTest::paint);
        StringBuilder failures = new StringBuilder();
        for (Fill fill : FILLS) {
            PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, fill.region(), TOLERANCE);
            System.out.println(fill.name() + ": max " + difference.max() + " levels, "
                    + difference.beyond() + " pixels beyond " + TOLERANCE);
            if (difference.beyond() > fill.allowedBeyond()) {
                failures.append(fill.name()).append(" (").append(difference.beyond()).append(" pixels, max ")
                        .append(difference.max()).append(" levels); ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("TexturePaint fills differ from the software loops: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        // new images every time: each is cached as a texture in the flush that samples it
        Rectangle fill = FILLS.get(0).region();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setPaint(new TexturePaint(PipelineComparison.fourColorTile(BufferedImage.TYPE_INT_RGB, OPAQUE_ALPHA),
                                           new Rectangle(0, 0, TILE, TILE)));
        graphics.fillRect(fill.x, fill.y, fill.width, fill.height);
        fill = FILLS.get(1).region();
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setPaint(new TexturePaint(PipelineComparison.fourColorTile(BufferedImage.TYPE_INT_ARGB, HALF_ALPHA),
                                           new Rectangle(2, 1, TILE, TILE)));   // an anchor off the origin
        graphics.fillRect(fill.x, fill.y, fill.width, fill.height);
        fill = FILLS.get(2).region();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(new TexturePaint(PipelineComparison.fourColorTile(BufferedImage.TYPE_INT_RGB, OPAQUE_ALPHA),
                                           new Rectangle(0, 0, TILE, TILE)));
        graphics.fill(new Ellipse2D.Float(fill.x, fill.y, fill.width, fill.height));
        fill = FILLS.get(3).region();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setPaint(new TexturePaint(PipelineComparison.fourColorTile(BufferedImage.TYPE_INT_RGB, OPAQUE_ALPHA),
                                           new Rectangle(fill.x, fill.y, 2 * TILE, 2 * TILE)));   // the tile scaled 2x
        graphics.fillRect(fill.x, fill.y, fill.width, fill.height);
    }
}
