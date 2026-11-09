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
 * @summary Regression test for JBR-10654: a blit reads its source as it is at the blit, also when
 *          the source is drawn into right after it in the same frame and blitted again.
 * @requires os.family == "windows"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaBlitSourceOrderTest
 * @run main/othervm -Dsun.java2d.uiScale=1 SkiaBlitSourceOrderTest
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * An accelerated image and a cached BufferedImage (its texture copy updated when the image
 * changes) are each drawn twice onto a surface with a repaint in between, and the first copy must
 * show the first content, the second copy the second; on the Skia pipeline and on the default one
 * (so this test runs without the pipeline's helpers, and never skips).
 */
public class SkiaBlitSourceOrderTest {

    /** The side of the two source images. */
    private static final int SOURCE_SIZE = 20;
    private static final int DESTINATION_WIDTH = 200;
    private static final int DESTINATION_HEIGHT = 40;
    /** How often the frame is rendered again when a volatile image lost its contents meanwhile. */
    private static final int ATTEMPTS = 5;
    /** A few draws of the BufferedImage: the proxy caches it as a texture after some uses. */
    private static final int WARMUP_DRAWS = 4;
    /** Where the four copies land: the accelerated image's two, then the cached image's two. */
    private static final int ACCELERATED_FIRST_X = 0;
    private static final int ACCELERATED_SECOND_X = 40;
    private static final int CACHED_FIRST_X = 100;
    private static final int CACHED_SECOND_X = 140;
    /** The warm-up draws go where no checked copy lands. */
    private static final int WARMUP_X = 150;
    /** The sample point inside each copy, away from its edges. */
    private static final int SAMPLE_OFFSET = 10;

    public static void main(String[] args) {
        GraphicsConfiguration graphicsConfig = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage destination = graphicsConfig.createCompatibleVolatileImage(DESTINATION_WIDTH, DESTINATION_HEIGHT,
                                                                                 Transparency.OPAQUE);
        VolatileImage accelerated = graphicsConfig.createCompatibleVolatileImage(SOURCE_SIZE, SOURCE_SIZE, Transparency.OPAQUE);
        BufferedImage cached = new BufferedImage(SOURCE_SIZE, SOURCE_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            destination.validate(graphicsConfig);
            accelerated.validate(graphicsConfig);
            fill(accelerated, Color.RED);
            fill(cached, Color.RED);
            Graphics2D graphics = destination.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, DESTINATION_WIDTH, DESTINATION_HEIGHT);
            for (int draw = 0; draw < WARMUP_DRAWS; draw++) {
                graphics.drawImage(cached, WARMUP_X, 0, null);   // the image gets its texture copy
            }
            graphics.dispose();
            destination.getSnapshot();   // ends the frame: the sources were last drawn in an earlier one

            graphics = destination.createGraphics();
            graphics.drawImage(accelerated, ACCELERATED_FIRST_X, 0, null);    // red
            graphics.drawImage(cached, CACHED_FIRST_X, 0, null);              // red
            fill(accelerated, Color.BLUE);
            fill(cached, Color.BLUE);
            graphics.drawImage(accelerated, ACCELERATED_SECOND_X, 0, null);   // blue
            graphics.drawImage(cached, CACHED_SECOND_X, 0, null);             // blue
            graphics.dispose();
            BufferedImage snapshot = destination.getSnapshot();
            if (destination.contentsLost() || accelerated.contentsLost()) {
                continue;
            }
            check(snapshot, ACCELERATED_FIRST_X + SAMPLE_OFFSET, "accelerated, first copy", Color.RED);
            check(snapshot, ACCELERATED_SECOND_X + SAMPLE_OFFSET, "accelerated, second copy", Color.BLUE);
            check(snapshot, CACHED_FIRST_X + SAMPLE_OFFSET, "cached, first copy", Color.RED);
            check(snapshot, CACHED_SECOND_X + SAMPLE_OFFSET, "cached, second copy", Color.BLUE);
            System.out.println("PASSED");
            return;
        }
        throw new RuntimeException("the surfaces lost their contents " + ATTEMPTS + " times in a row");
    }

    private static void fill(Image image, Color color) {
        Graphics2D graphics = (Graphics2D) image.getGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, SOURCE_SIZE, SOURCE_SIZE);
        graphics.dispose();
    }

    private static void check(BufferedImage snapshot, int x, String what, Color expected) {
        int actualRGB = snapshot.getRGB(x, SAMPLE_OFFSET) & 0xffffff;
        int expectedRGB = expected.getRGB() & 0xffffff;
        if (actualRGB != expectedRGB) {
            throw new RuntimeException(what + ": " + Integer.toHexString(actualRGB) + ", expected "
                                       + Integer.toHexString(expectedRGB));
        }
    }
}
