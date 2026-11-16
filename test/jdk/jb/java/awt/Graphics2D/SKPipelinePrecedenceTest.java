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
import java.awt.image.VolatileImage;

import jtreg.SkippedException;
import sun.java2d.vulkan.VKEnv;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: when Skia is requested together with another GPU pipeline
 *          and starts, it owns the default configuration, its volatile images and the windows alone.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d.skia
 *          java.desktop/sun.java2d.vulkan
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.d3d=true SKPipelinePrecedenceTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.opengl=true SKPipelinePrecedenceTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.vulkan=Force SKPipelinePrecedenceTest skia
 * @run main/othervm -Dsun.java2d.d3d=true SKPipelinePrecedenceTest d3d
 */

/*
 * Without this, -Dsun.java2d.d3d=true created Direct3D screen devices whose configurations made
 * the volatile images (Swing's back buffer) Direct3D surfaces, while the screen update manager
 * went to Skia and the windows fell back to GDI surfaces. When Skia is not requested, the other
 * pipeline runs as before. The run line's argument names the pipeline expected everywhere. A
 * forced Vulkan pipeline yields to Skia altogether: it does not start next to it.
 */

/** The package of the Direct3D pipeline's configurations and surfaces. */
private static final String D3D_PACKAGE = ".d3d.";

private static final int PROBE_SIZE = 16;

void main(String[] args) throws Exception {
    String expectedPipeline = args[0];
    boolean skiaExpected = expectedPipeline.equals("skia");
    if (skiaExpected) {
        PipelineComparison.requireSkia();
    }
    String pipelinePackage = skiaExpected ? PipelineComparison.SKIA_PACKAGE : D3D_PACKAGE;
    GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
    String configurationName = graphicsConfig.getClass().getName();
    if (!skiaExpected && !configurationName.contains(pipelinePackage)) {
        throw new SkippedException("Direct3D unavailable (" + configurationName + "): nothing to test");
    }
    VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(PROBE_SIZE, PROBE_SIZE);
    volatileImage.validate(graphicsConfig);
    String volatileSurface = PipelineComparison.surfaceClassName(volatileImage);
    String windowSurface = WindowSurfaces.ofNewFrame("SKPipelinePrecedenceTest",
                                                     surface -> surface.contains(pipelinePackage));

    String found = "configuration " + configurationName + ", volatile image " + volatileSurface
            + ", window " + windowSurface;
    System.out.println(found);
    if (!configurationName.contains(pipelinePackage) || !volatileSurface.contains(pipelinePackage)
            || !windowSurface.contains(pipelinePackage)) {
        throw new RuntimeException("expected the " + expectedPipeline + " pipeline everywhere: " + found);
    }
    if (skiaExpected && VKEnv.isVulkanEnabled()) {
        throw new RuntimeException("the Vulkan pipeline started next to Skia");
    }
    System.out.println("PASSED");
}
