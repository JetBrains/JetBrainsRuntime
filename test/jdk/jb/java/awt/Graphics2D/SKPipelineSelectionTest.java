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

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.VolatileImage;
import java.util.Arrays;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: when Skia is requested and starts, every screen
 *          device's configurations are Skia's alone, whatever other pipeline is requested too.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.vulkan=true SKPipelineSelectionTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.opengl=true SKPipelineSelectionTest
 */

/*
 * A Vulkan request used to replace the Skia configuration after it had been created. Skia takes
 * every screen device (Win32GraphicsDevice), and each has the one Skia configuration: a window
 * moved to a screen on GDI would not update any more (SKScreenMoveTest).
 */

/** The side of the volatile image that probes which pipeline owns the configuration's surfaces. */
private static final int PROBE_SIZE = 16;

void main() {
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
        System.out.println((device == defaultScreen ? "default " : "") + "screen device " + device.getIDstring()
                + ": " + configurations.length + " configurations, " + skiaConfigurations + " of them Skia's");
        if (skiaConfigurations != 1 || configurations.length != 1) {
            throw new RuntimeException("expected the one Skia configuration on screen device " + device.getIDstring()
                    + ": " + Arrays.toString(configurations));
        }
    }
    System.out.println("PASSED");
}
