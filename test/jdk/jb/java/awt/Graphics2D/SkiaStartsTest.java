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
 * @summary Regression test for JBR-10654: a runtime built with Skia starts the Skia backend on
 *          request unless both Dawn and Vulkan lack an adapter or are blocked by the GPU list.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.pipelineCache=off SkiaStartsTest
 */

import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/**
 * The sentinel for the directory's skips: the other tests skip when Skia is not enabled, so a
 * start that broke (the library not loading, a device that does not start, an exception in the
 * start-up) would leave a suite of skips and no failure. Here, on a runtime with libawt_skia,
 * the backend must start; the only accepted reasons for not starting are the ones the start
 * reports for a machine without a usable GPU: no adapter, or a GPU list entry, for Dawn and for
 * Vulkan both.
 */
public class SkiaStartsTest {

    public static void main(String[] args) {
        PipelineComparison.requireSkiaLibrary();
        String verbose = PipelineComparison.startCapturingVerbose();
        String runningOn = SKEnvironment.runningOn();
        if (runningOn != null) {
            System.out.println("PASSED: " + runningOn);
            return;
        }
        boolean dawnUnusable = unusable(verbose, "Dawn");
        boolean vulkanUnusable = unusable(verbose, "Vulkan");
        if (dawnUnusable && vulkanUnusable) {
            throw new SkippedException("neither Dawn nor Vulkan has a usable adapter here: nothing to test\n" + verbose);
        }
        throw new RuntimeException("Skia did not start on a runtime built with it, and the start names no missing "
                + "or blocked adapter for " + (dawnUnusable ? "Vulkan" : "Dawn") + ":\n" + verbose);
    }

    /** Whether the start reported the API as without an adapter or blocked (SKGraphicsAPIFallback.startFirstUsableAPI). */
    private static boolean unusable(String verbose, String api) {
        return verbose.lines().anyMatch(line -> line.equals("Skia on " + api + ": no adapter")
                || line.startsWith("Skia on " + api + " not used on ") && line.contains(": GPU list entry "));
    }
}
