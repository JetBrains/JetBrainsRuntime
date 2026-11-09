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
 * @summary Regression test for JBR-10654: with LOCALAPPDATA a path Windows does not accept, the
 *          Skia backend starts without a pipeline cache instead of failing the graphics environment.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaInvalidCacheLocationTest
 */

import java.awt.Color;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKPipelineCache;

/**
 * What the start-up reads from the environment cannot keep the graphics environment from starting:
 * the default pipeline cache location lies under LOCALAPPDATA, and an invalid one leaves the
 * backend without a cache instead of throwing InvalidPathException out of the construction of the
 * GraphicsEnvironment; it says so without a stack trace on stderr, and nothing is stored.
 */
public class SkiaInvalidCacheLocationTest {

    /** A LOCALAPPDATA with characters Windows does not allow in a path ('|', '<', '>'). */
    private static final String INVALID_LOCAL_APP_DATA = "C:\\not|a<valid>path";

    /** The side of the volatile image the child draws into, enough for a pipeline to compile. */
    private static final int IMAGE_SIZE = 32;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        PipelineComparison.requireSkia();
        // the default cache location is the subject: no sun.java2d.skia.pipelineCache, and not the suite's VM options
        ProcessBuilder processBuilder = ProcessTools.createLimitedTestJavaProcessBuilder(
                PipelineComparison.skiaChildCommand(
                        List.of("-Dsun.java2d.skia.verbose=true", "-cp", System.getProperty("test.class.path")),
                        SkiaInvalidCacheLocationTest.class, "child"));
        processBuilder.environment().put("LOCALAPPDATA", INVALID_LOCAL_APP_DATA);
        OutputAnalyzer childOutput = ProcessTools.executeProcess(processBuilder);
        System.out.println(childOutput.getStdout());
        System.out.println(childOutput.getStderr());
        childOutput.shouldHaveExitValue(0);
        childOutput.shouldContain("skia enabled: true");
        childOutput.stderrShouldContain("Skia pipeline cache off: ");   // SKPipelineCache.resolveDirectory
        childOutput.shouldContain("pipeline cache stores: 0");
        childOutput.stderrShouldNotMatch("\\tat [a-z]");   // no stack trace
        System.out.println("PASSED");
    }

    private static void child() {
        PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), IMAGE_SIZE, IMAGE_SIZE, graphics -> {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        });
        System.out.println("skia enabled: " + SKEnvironment.isSkiaEnabled() + " (" + SKEnvironment.runningOn() + ")");
        System.out.println("pipeline cache stores: "
                           + SKPipelineCache.counters()[PipelineComparison.PipelineCacheCounter.STORES]);
    }
}
