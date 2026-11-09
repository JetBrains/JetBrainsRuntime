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
 * @summary Regression test for JBR-10654: a TexturePaint fill on the Skia pipeline samples its image
 *          also when the image's cached texture is gone or stale by the time the paint is sent.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaTexturePaintSourceTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaTexturePaintSourceTest skia
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.List;

/**
 * The paint is accepted once the image is cached as a texture (SKPaints, outside the queue
 * lock); BufferedPaints looks the image up again when it sends the paint, under the lock, in the
 * middle of the validation. After an Image.flush the proxy created and filled a new texture
 * there, which validated the texture as the destination, and the fill went into it: nothing
 * appeared. After a modification the lookup handed the image's software surface: the backend
 * logged it, dropped the paint and the fill came out in the plain color. Now no texture is made
 * under the lock, and a software source is uploaded for the paint. Deterministic here: a fill
 * validates the paint, a draw into another surface switches the context (the paint is sent again
 * on the next fill), the image is flushed or modified, and the next fill (same Graphics, same
 * paint) follows. Opaque and translucent images; compared with the software loops.
 */
public class SkiaTexturePaintSourceTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    /** Sampling a texture against the loops' texel lookup: rounding of translucent colors. */
    private static final int TOLERANCE = 4;
    /** The side of the other surface drawn into for the context switch. */
    private static final int OTHER_SIZE = 8;
    /** A few fills: the proxy caches the image as a texture after some uses. */
    private static final int FILLS = 4;
    /** The fills' rows: 20 high, 25 apart; the left half before, the right half after the flush or modification. */
    private static final int FILL_WIDTH = 60;
    private static final int FILL_HEIGHT = 20;
    private static final int FILL_PITCH = 25;
    private static final int LEFT_X = 10;
    private static final int RIGHT_X = 90;
    private static final int FIRST_Y = 10;
    /** The alpha of the translucent tiles. */
    private static final int TRANSLUCENT_ALPHA = 160;
    private static final int OPAQUE_ALPHA = 255;

    /** How the image's cached texture is made unusable between the fills. */
    private enum Invalidation { FLUSH, MODIFY }

    /** The tile's image type and alpha. */
    private record ImageCase(int imageType, int alpha) {
    }

    private static final List<ImageCase> CASES = List.of(
            new ImageCase(BufferedImage.TYPE_INT_RGB, OPAQUE_ALPHA),
            new ImageCase(BufferedImage.TYPE_INT_ARGB, TRANSLUCENT_ALPHA),
            new ImageCase(BufferedImage.TYPE_INT_ARGB_PRE, TRANSLUCENT_ALPHA));

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        StringBuilder failures = new StringBuilder();
        for (Invalidation invalidation : Invalidation.values()) {
            for (ImageCase imageCase : CASES) {
                BufferedImage referenceTile = PipelineComparison.fourColorTile(imageCase.imageType(), imageCase.alpha());
                BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT,
                        graphics -> paint(graphics, referenceTile, null, invalidation));
                BufferedImage tile = PipelineComparison.fourColorTile(imageCase.imageType(), imageCase.alpha());
                VolatileImage other = graphicsConfig.createCompatibleVolatileImage(OTHER_SIZE, OTHER_SIZE);
                other.validate(graphicsConfig);
                BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT,
                        graphics -> paint(graphics, tile, other, invalidation));
                PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
                String what = invalidation.name().toLowerCase() + ", image type " + imageCase.imageType() + ", alpha "
                              + imageCase.alpha();
                System.out.println(what + ": max " + difference.max() + " levels, " + difference.beyond() + " pixels beyond "
                                   + TOLERANCE);
                if (difference.beyond() > 0) {
                    failures.append(what).append(" (").append(difference.beyond()).append(" pixels, max ")
                            .append(difference.max()).append(" levels); ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("TexturePaint fills after the image was flushed or modified differ: " + failures);
        }
        System.out.println("PASSED");
    }

    /**
     * Fills the left half with the TexturePaint (it is validated and its image cached),
     * draws into another surface (a context switch: the paint is sent again on the next
     * fill), flushes the image (its cached texture goes) or modifies it (its cached texture
     * is stale) and fills the right half with the same Graphics and paint.
     */
    private static void paint(Graphics2D graphics, BufferedImage tile, VolatileImage other, Invalidation invalidation) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(Color.BLACK);   // the plain color a dropped paint falls back to
        graphics.setPaint(new TexturePaint(tile, new Rectangle(0, 0, PipelineComparison.TILE_SIZE, PipelineComparison.TILE_SIZE)));
        for (int fill = 0; fill < FILLS; fill++) {
            graphics.fillRect(LEFT_X, FIRST_Y + FILL_PITCH * fill, FILL_WIDTH, FILL_HEIGHT);
        }
        if (other != null) {
            Graphics2D otherGraphics = other.createGraphics();
            otherGraphics.setColor(Color.GRAY);
            otherGraphics.fillRect(0, 0, OTHER_SIZE, OTHER_SIZE);
            otherGraphics.dispose();
        }
        switch (invalidation) {
            case FLUSH -> tile.flush();
            case MODIFY -> tile.setRGB(0, 0, tile.getRGB(0, 0));   // the raster changes: the cached texture is stale
        }
        for (int fill = 0; fill < FILLS; fill++) {
            graphics.fillRect(RIGHT_X, FIRST_Y + FILL_PITCH * fill, FILL_WIDTH, FILL_HEIGHT);
        }
    }
}
