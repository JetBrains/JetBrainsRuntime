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
 */

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.image.VolatileImage;

import sun.java2d.SurfaceData;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary When the Skia backend is requested and available, the default
 *          configuration renders on it, whatever other pipeline the command
 *          line also requests: on Windows a Vulkan request used to replace the
 *          Skia configuration after it had been created, on macOS an OpenGL
 *          request is overridden by design. When Skia is unavailable (not
 *          built, no GPU) there is nothing to test.
 * @key headful
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.vulkan=true PipelineSelectionTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.opengl=true PipelineSelectionTest
 */
public class PipelineSelectionTest {

    public static void main(String[] args) {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage image = gc.createCompatibleVolatileImage(16, 16);
        image.validate(gc);
        String surface = SurfaceData.getPrimarySurfaceData(image).getClass().getName();
        System.out.println("configuration " + gc.getClass().getName() + ", surface " + surface);
        if (!surface.contains(".skia.")) {
            throw new RuntimeException("Skia is enabled but the default configuration renders on " + surface);
        }
        System.out.println("PASSED");
    }
}
