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
 * @summary Regression test for JBR-10654: LCD text still queued in a glyph batch is drawn into its
 *          Skia surface before the surface is flushed or invalidated, in every LCD compositing mode.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=blend -Dsun.java2d.uiScale=1 LCDTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=twoPass -Dsun.java2d.uiScale=1 LCDTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=accumulate -Dsun.java2d.uiScale=1 LCDTextDisposeTest skia
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.java2d.SurfaceData;

/**
 * The batch has to be drawn into that surface before the pipeline forgets it. The accumulate
 * compositing (sun.java2d.skia.text.lcdCompositing=accumulate) composites the coverage onto the
 * current destination from a hook, so it is the one that notices if the destination has already
 * been dropped.
 */
public class LCDTextDisposeTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 40;
    /** Wide: the readback checks that the pipeline is still in order, not the compositing's accuracy. */
    private static final int TOLERANCE = 48;
    /** Surfaces flushed and invalidated under a queued batch: enough for the glyph caches to wrap round. */
    private static final int ROUNDS = 300;
    private static final int FONT_SIZE = 13;
    /** The text color's base; each round adds its number so that no two rounds draw the same glyph images. */
    private static final int BASE_COLOR = 0x202020;
    private static final int LEFT_MARGIN = 4;
    private static final int FIRST_BASELINE = 15;
    private static final int SECOND_BASELINE = 31;
    /** Every so many rounds one more image is read back and compared, so that a pipeline put out of order mid-way shows. */
    private static final int READBACK_EVERY = 50;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        for (int round = 0; round < ROUNDS; round++) {
            // text queued, then the image flushed: the queued batch meets a surface on its way out
            VolatileImage image = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
            paintInto(image, round);
            image.flush();
            // text queued, then the surface invalidated: the pipeline forgets
            // the surface while the batch still refers to it, the texture stays
            image = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
            paintInto(image, round);
            SurfaceData.getPrimarySurfaceData(image).invalidate();
            if (round % READBACK_EVERY == 0) {
                checkAgainstSoftware(graphicsConfig, round, "after " + round + " rounds");
            }
        }
        // and one that is read back: the pipeline is still in order
        checkAgainstSoftware(graphicsConfig, 0, "after flushed and invalidated surfaces");
        System.out.println("PASSED");
    }

    /** Renders the round's text, reads it back and compares it with the software loops. */
    private static void checkAgainstSoftware(GraphicsConfiguration graphicsConfig, int round, String when) {
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, graphics -> paint(graphics, round));
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, graphics -> paint(graphics, round));
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(when + ": " + difference.beyond() + " pixels beyond " + TOLERANCE + " levels, max " + difference.max());
        if (difference.beyond() > 0) {
            throw new RuntimeException("LCD text " + when + " differs from the software loops: "
                    + difference.beyond() + " pixels beyond " + TOLERANCE + " levels");
        }
    }

    /** Queues the round's text into the image without reading it back. */
    private static void paintInto(VolatileImage image, int round) {
        Graphics2D graphics = image.createGraphics();
        try {
            paint(graphics, round);
        } finally {
            graphics.dispose();
        }
    }

    private static void paint(Graphics2D graphics, int round) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, FONT_SIZE));
        graphics.setColor(new Color(BASE_COLOR + round));
        graphics.drawString("Hamburgefonstiv " + round, LEFT_MARGIN, FIRST_BASELINE);
        graphics.drawString("Hamburgefonstiv " + round, LEFT_MARGIN + 1, SECOND_BASELINE);
    }
}
