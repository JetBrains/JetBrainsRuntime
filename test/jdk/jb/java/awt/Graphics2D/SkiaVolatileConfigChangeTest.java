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
 * @summary Regression test for JBR-10654: a component's VolatileImage created on a Skia
 *          configuration keeps working when a display change leaves its component on GDI.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.awt java.desktop/sun.awt.image java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaVolatileConfigChangeTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaVolatileConfigChangeTest
 */

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.awt.AWTAccessor;
import sun.awt.Win32GraphicsConfig;
import sun.awt.Win32GraphicsDevice;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.image.VolatileSurfaceManager;

/**
 * A window on a screen Skia does not take, or a device recovery that ends without a Skia
 * configuration, leaves the component on a GDI configuration: the display change moves the image
 * to the component's configuration, and validating and drawing it there renders in software
 * instead of throwing ClassCastException from every validate().
 */
public class SkiaVolatileConfigChangeTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    /** The border of white around the colored rectangle. */
    private static final int BORDER = 8;
    /** Frames drawn after the display change, in alternating colors. */
    private static final int FRAMES = 3;
    private static final int WHITE = 0xffffff;

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireRequestedAPI();
        GraphicsConfiguration skiaConfig = PipelineComparison.defaultConfiguration();
        Component component = new Canvas();
        component.setForeground(Color.BLACK);
        component.setBackground(Color.WHITE);
        AWTAccessor.getComponentAccessor().setGraphicsConfiguration(component, skiaConfig);
        VolatileImage image = new SunVolatileImage(component, WIDTH, HEIGHT);
        image.validate(skiaConfig);
        draw(image, Color.RED);
        String before = PipelineComparison.surfaceClassName(image);
        System.out.println("before the display change: " + before + " on " + skiaConfig);
        if (!before.contains(PipelineComparison.SKIA_PACKAGE)) {
            throw new RuntimeException("the image is not on Skia: " + before);
        }

        // the device's first (GDI) configuration
        GraphicsConfiguration gdiConfig = Win32GraphicsConfig.getConfig((Win32GraphicsDevice) skiaConfig.getDevice(), 0);
        AWTAccessor.getComponentAccessor().setGraphicsConfiguration(component, gdiConfig);
        ((VolatileSurfaceManager) SurfaceManager.getManager(image)).displayChanged();

        for (int frame = 0; frame < FRAMES; frame++) {
            int status = image.validate(gdiConfig);
            Color color = frame % 2 == 0 ? Color.BLUE : Color.GREEN;
            draw(image, color);
            BufferedImage snapshot = image.getSnapshot();
            int center = snapshot.getRGB(WIDTH / 2, HEIGHT / 2) & 0xffffff;
            int corner = snapshot.getRGB(1, 1) & 0xffffff;
            System.out.printf("frame %d: validate %d, surface %s, center %06x, corner %06x%n",
                    frame, status, PipelineComparison.surfaceClassName(image), center, corner);
            if (center != (color.getRGB() & 0xffffff) || corner != WHITE) {
                throw new RuntimeException("frame " + frame + " after the display change: center " + Integer.toHexString(center)
                        + ", corner " + Integer.toHexString(corner));
            }
        }
        System.out.println("PASSED");
    }

    /** White, with a rectangle of the color inside a border. */
    private static void draw(VolatileImage image, Color color) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(color);
            graphics.fillRect(BORDER, BORDER, WIDTH - 2 * BORDER, HEIGHT - 2 * BORDER);
        } finally {
            graphics.dispose();
        }
    }
}
