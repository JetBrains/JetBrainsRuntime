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
 * @summary Regression test for JBR-10654: a flushed Skia surface counts as lost at once, and its
 *          image is restored on a new surface.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaFlushedSurfaceTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaFlushedSurfaceTest skia
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.java2d.SurfaceData;

/**
 * A flushed Skia surface (VolatileImage.flush(), a replaced cached texture) gives its texture back
 * at once (SKSurfaceData.invalidate, FLUSH_SURFACE) instead of when the Disposer collects the Java
 * surface; the surface counts as lost from then on, so that whoever still holds it (a stale
 * source) fails validation rather than draws a texture that is gone, and the image is restored on
 * a new one. That the texture itself goes is not observable from here.
 */
public class SkiaFlushedSurfaceTest {

    private static final int IMAGE_SIZE = 64;
    /** The blue the image is filled with after the flush, as an RGB value. */
    private static final int BLUE = 0x0000ff;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(args[0], graphicsConfig);
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(IMAGE_SIZE, IMAGE_SIZE, Transparency.OPAQUE);
        volatileImage.validate(graphicsConfig);
        fill(volatileImage, Color.RED);
        SurfaceData flushed = SurfaceData.getPrimarySurfaceData(volatileImage);
        volatileImage.flush();
        System.out.println("flushed " + flushed + ": lost " + flushed.isSurfaceLost() + ", valid " + flushed.isValid());
        if (!flushed.isSurfaceLost()) {
            throw new RuntimeException("a flushed Skia surface does not count as lost");
        }
        int status = volatileImage.validate(graphicsConfig);
        fill(volatileImage, Color.BLUE);
        BufferedImage snapshot = volatileImage.getSnapshot();
        int center = snapshot.getRGB(IMAGE_SIZE / 2, IMAGE_SIZE / 2) & 0xffffff;
        System.out.printf("after the flush: validate %d, surface %s, pixel %06x%n", status,
                PipelineComparison.surfaceClassName(volatileImage), center);
        if (center != BLUE || SurfaceData.getPrimarySurfaceData(volatileImage) == flushed) {
            throw new RuntimeException("the image was not restored on a new surface after its flush");
        }
        System.out.println("PASSED");
    }

    private static void fill(VolatileImage volatileImage, Color color) {
        Graphics2D graphics = volatileImage.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, volatileImage.getWidth(), volatileImage.getHeight());
        graphics.dispose();
    }
}
