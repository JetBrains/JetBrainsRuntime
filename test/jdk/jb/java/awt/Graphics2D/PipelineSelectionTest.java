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
 * @summary Regression test for JBR-10654: when Skia is requested and starts, the default screen
 *          device's configurations are Skia's alone, whatever other pipeline is requested too.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.vulkan=true PipelineSelectionTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.opengl=true PipelineSelectionTest
 */

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.VolatileImage;
import java.util.Arrays;

/**
 * A Vulkan request used to replace the Skia configuration after it had been created. Skia takes
 * the default screen device only (Win32GraphicsDevice, as OpenGL does): that device has the one
 * Skia configuration, every other screen device none (with one display there is no other).
 */
public class PipelineSelectionTest {

    /** The side of the volatile image that probes which pipeline owns the configuration's surfaces. */
    private static final int PROBE_SIZE = 16;

    public static void main(String[] args) {
        PipelineComparison.requireSkia();
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice defaultScreen = graphicsEnvironment.getDefaultScreenDevice();
        GraphicsConfiguration graphicsConfig = defaultScreen.getDefaultConfiguration();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(PROBE_SIZE, PROBE_SIZE);
        volatileImage.validate(graphicsConfig);
        String surface = PipelineComparison.surfaceClassName(volatileImage);
        System.out.println("configuration " + graphicsConfig.getClass().getName() + ", surface " + surface);
        if (!surface.contains(PipelineComparison.SKIA_PACKAGE)) {
            throw new RuntimeException("Skia is enabled but the default configuration renders on " + surface);
        }
        for (GraphicsDevice device : graphicsEnvironment.getScreenDevices()) {
            GraphicsConfiguration[] configurations = device.getConfigurations();
            long skiaConfigurations = Arrays.stream(configurations)
                    .filter(configuration -> configuration.getClass().getName().contains(PipelineComparison.SKIA_PACKAGE))
                    .count();
            boolean isDefault = device == defaultScreen;
            System.out.println((isDefault ? "default " : "") + "screen device " + device.getIDstring() + ": "
                    + configurations.length + " configurations, " + skiaConfigurations + " of them Skia's");
            boolean asExpected = isDefault ? skiaConfigurations == 1 && configurations.length == 1 : skiaConfigurations == 0;
            if (!asExpected) {
                throw new RuntimeException("expected " + (isDefault ? "the one Skia configuration" : "no Skia configuration")
                        + " on screen device " + device.getIDstring() + ": " + Arrays.toString(configurations));
            }
        }
        System.out.println("PASSED");
    }
}
