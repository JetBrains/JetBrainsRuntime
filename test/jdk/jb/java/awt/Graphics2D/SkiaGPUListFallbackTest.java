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
 * @summary Regression test for JBR-10654: the Skia backend tries the graphics APIs in order (Dawn,
 *          then Vulkan) and asks the GPU list before each; when the list blocks one it falls back
 *          to the next, and when it blocks both the backend stays off.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.gpuBlocklist=${test.src}/gpulists/block-dawn.gpulist SkiaGPUListFallbackTest vulkan test/dawn
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.gpuBlocklist=${test.src}/gpulists/block-all.gpulist SkiaGPUListFallbackTest off test/everything
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.skia.gpuBlocklist=${test.src}/gpulists/block-vulkan.gpulist SkiaGPUListFallbackTest dawn test/vulkan
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.gpuBlocklist=ignore SkiaGPUListFallbackTest dawn -
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.gpuBlocklist=${test.src}/gpulists/broken.gpulist SkiaGPUListFallbackTest dawn cannot be used
 */

import java.awt.GraphicsConfiguration;
import java.awt.image.VolatileImage;
import java.util.Arrays;

import sun.java2d.skia.SKEnvironment;

/**
 * With sun.java2d.skia.gpuBlocklist naming a list that blocks Dawn, Vulkan renders (and Dawn with
 * graphite.api=vulkan and Vulkan blocked); with one that blocks both, the backend is off and the
 * platform's default pipeline renders the default configuration, its volatile images and windows,
 * as without sun.java2d.skia; =ignore applies no list; a list that cannot be parsed leaves the
 * shipped one in place with a warning. verbose names the entry that kept an API from being used
 * (the second argument on the run line); a machine with no adapter for either API never consults
 * the list.
 */
public class SkiaGPUListFallbackTest {

    /** The run line's second argument when verbose need not mention anything. */
    private static final String NO_MENTION = "-";

    /** The side of the volatile image that probes which pipeline renders. */
    private static final int PROBE_SIZE = 16;

    public static void main(String[] args) throws Exception {
        String expectedAPI = args[0];
        String mention = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        PipelineComparison.requireSkiaLibrary();
        String verbose = PipelineComparison.startCapturingVerbose();
        String runningOn = SKEnvironment.runningOn();

        if (expectedAPI.equals("off")) {
            if (runningOn != null) {
                throw new RuntimeException("the list blocks Dawn and Vulkan, yet Skia started: " + runningOn);
            }
            checkDefaultPipeline();
            if (!verbose.contains(mention) && verbose.contains("Skia on Dawn: no adapter")
                    && verbose.contains("Skia on Vulkan: no adapter")) {
                System.out.println("no adapter for either API: the list was never consulted");
                System.out.println("PASSED");
                return;
            }
        } else {
            // Vulkan may be missing (no loader, no driver), Dawn may find no adapter or not start: the
            // backend then goes on to the other API or stays off, and says why (else this fails)
            PipelineComparison.requireAPI(expectedAPI.equals("vulkan") ? "Vulkan" : "Dawn", null, verbose);
        }
        if (!mention.equals(NO_MENTION) && !verbose.contains(mention)) {
            throw new RuntimeException("verbose does not mention '" + mention + "':\n" + verbose);
        }
        System.out.println("PASSED");
    }

    /** No Skia surface anywhere: the configuration, a volatile image and a window are the platform's. */
    private static void checkDefaultPipeline() throws Exception {
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(PROBE_SIZE, PROBE_SIZE);
        volatileImage.validate(graphicsConfig);
        String volatileSurface = PipelineComparison.surfaceClassName(volatileImage);
        String windowSurface = WindowSurfaces.ofNewFrame("SkiaGPUListFallbackTest",
                surface -> !surface.equals(WindowSurfaces.NONE) && !surface.contains(PipelineComparison.SKIA_PACKAGE));
        String configurationName = graphicsConfig.getClass().getName();
        String found = "configuration " + configurationName + ", volatile image " + volatileSurface
                       + ", window " + windowSurface;
        System.out.println(found);
        if ((configurationName + volatileSurface + windowSurface).contains(PipelineComparison.SKIA_PACKAGE)) {
            throw new RuntimeException("Skia is off, yet: " + found);
        }
    }
}
