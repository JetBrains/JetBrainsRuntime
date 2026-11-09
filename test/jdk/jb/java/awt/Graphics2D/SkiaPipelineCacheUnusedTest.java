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
 * @summary Regression test for JBR-10654: the Skia pipeline cache's directory is created when the
 *          first pipeline is stored, so a backend that does not start leaves nothing in the profile.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaPipelineCacheUnusedTest
 */

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;

/**
 * With -Dsun.java2d.skia=true and a GPU list that blocks Dawn and Vulkan on every adapter, Skia is
 * off and nothing is created under LOCALAPPDATA, which for the child points into the test's directory.
 */
public class SkiaPipelineCacheUnusedTest {

    /** The side of the volatile image the child draws into (on the platform's default pipeline here). */
    private static final int IMAGE_SIZE = 32;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        PipelineComparison.requireSkia();
        Path localAppData = Path.of("localappdata-unused").toAbsolutePath();
        Files.createDirectories(localAppData);
        Path blockAllList = Path.of(System.getProperty("test.src"), "gpulists", "block-all.gpulist");
        // the default cache location is the subject: no sun.java2d.skia.pipelineCache, and not the suite's VM options
        ProcessBuilder processBuilder = ProcessTools.createLimitedTestJavaProcessBuilder(
                PipelineComparison.skiaChildCommand(
                        List.of("-Dsun.java2d.skia.verbose=true",
                                "-Dsun.java2d.skia.gpuBlocklist=" + blockAllList,
                                "-cp", System.getProperty("test.class.path")),
                        SkiaPipelineCacheUnusedTest.class, "child"));
        processBuilder.environment().put("LOCALAPPDATA", localAppData.toString());
        OutputAnalyzer childOutput = ProcessTools.executeProcess(processBuilder);
        System.out.println(childOutput.getStdout());
        System.out.println(childOutput.getStderr());
        childOutput.shouldHaveExitValue(0);
        childOutput.shouldContain("skia enabled: false");
        Path jetbrainsDirectory = localAppData.resolve("JetBrains");
        if (Files.exists(jetbrainsDirectory)) {
            throw new RuntimeException("Skia is off, yet " + jetbrainsDirectory + " was created");
        }
        System.out.println("PASSED");
    }

    private static void child() {
        PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), IMAGE_SIZE, IMAGE_SIZE, graphics -> {
            graphics.setColor(Color.RED);
            graphics.fillOval(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        });
        System.out.println("skia enabled: " + SKEnvironment.isSkiaEnabled());
    }
}
