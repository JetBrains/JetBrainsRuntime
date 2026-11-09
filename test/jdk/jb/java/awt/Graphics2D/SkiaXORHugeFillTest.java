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
 * @summary Regression test for JBR-10654: XOR fills far larger than a Skia surface, and at huge
 *          device coordinates, complete at once and render as the software loops do.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaXORHugeFillTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.graphite.api=vulkan SkiaXORHugeFillTest skia
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fills of 10^6 pixels and fills through a scale of 10^4. Under XOR without framebuffer fetch the
 * rect batch tests every quad for overlap in a grid of 64-pixel cells; the grid covered the
 * quad's whole unclipped extent (hundreds of millions of cells for a 10^6 px fill, an undefined
 * float-to-int conversion beyond 2^31), so the flush did not come back.
 */
public class SkiaXORHugeFillTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 120;
    /** XOR on both sides is exact: the results must be identical. */
    private static final int TOLERANCE = 0;
    /** Far longer than the fills take; a flush that walks the whole grid does not come back at all. */
    private static final long LIMIT_SECONDS = 20;
    /** A fill of 10^6 pixels a side: hundreds of millions of 64-pixel grid cells if the grid is not clipped. */
    private static final int HUGE = 1_000_000;
    /** A scale that puts user-space fills at device coordinates beyond 2^31. */
    private static final int HUGE_SCALE = 10_000;
    /** The user-space extent of the fills under the scale: 10^9 device pixels a side. */
    private static final int SCALED_HALF_EXTENT = 100_000;
    /** The exit code when the fills hang: not 1 (an exception) and not 0. */
    private static final int HUNG_EXIT_CODE = 97;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, SkiaXORHugeFillTest::paint);
        long start = System.nanoTime();
        CompletableFuture<BufferedImage> rendering = CompletableFuture.supplyAsync(
                () -> PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, SkiaXORHugeFillTest::paint));
        BufferedImage actual;
        try {
            actual = rendering.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            // the flusher is stuck in native code holding the queue lock: an orderly exit would wait for it
            System.out.println("FAILED: the XOR fills did not complete in " + LIMIT_SECONDS + " s");
            System.out.flush();
            Runtime.getRuntime().halt(HUNG_EXIT_CODE);
            return;
        }
        System.out.printf("rendered in %.1f ms%n", (System.nanoTime() - start) / 1e6);
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(difference.beyond() + " pixels differ from the software loops, max "
                + difference.max() + " levels");
        if (difference.beyond() > 0) {
            throw new RuntimeException("huge XOR fills differ from the software loops in "
                    + difference.beyond() + " pixels");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(new Color(40, 90, 160));
        graphics.fillRect(20, 20, 60, 40);
        graphics.setXORMode(Color.WHITE);
        graphics.setColor(new Color(200, 60, 20));
        // twice: the second fill is tested for overlap with the first
        graphics.fillRect(0, 0, HUGE, HUGE);
        graphics.fillRect(-HUGE, -HUGE, 2 * HUGE, 2 * HUGE);
        graphics.fillRect(50, 30, HUGE, 40);
        // huge device coordinates through a scale (non-AA parallelograms)
        Graphics2D scaled = (Graphics2D) graphics.create();
        scaled.scale(HUGE_SCALE, HUGE_SCALE);
        scaled.fillRect(-SCALED_HALF_EXTENT, -SCALED_HALF_EXTENT, 2 * SCALED_HALF_EXTENT, 2 * SCALED_HALF_EXTENT);
        scaled.fillRect(0, 0, 1, 1);
        scaled.dispose();
        graphics.setPaintMode();
    }
}
