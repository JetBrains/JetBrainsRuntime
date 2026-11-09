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
 * @summary Regression test for JBR-10654: without sun.java2d.skia.pipelineCache the Skia backend
 *          keeps its compiled pipelines under %LOCALAPPDATA%\JetBrains\JBR\SkiaPipelineCache\<Skia release>.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaPipelineCacheLocationTest
 */

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKPipelineCache;

/**
 * The location is keyed by the pinned Skia (whose and Dawn's formats it holds), not by the
 * runtime's version, so a runtime update with the same Skia keeps it and directories accumulate
 * only per Skia bump. The child runs with LOCALAPPDATA pointing into the test's directory, under a
 * name with non-ASCII characters (as a user name may have) and, under jtreg, a path longer than
 * MAX_PATH: the files are opened by their UTF-16 paths.
 */
public class SkiaPipelineCacheLocationTest {

    /** A profile directory name with umlauts and CJK characters, as a user name may have. */
    private static final String LOCAL_APP_DATA_NAME = "localappdata-äöü-日本";
    /** The side of the volatile image the child draws into, enough for a few pipelines to compile. */
    private static final int IMAGE_SIZE = 64;
    /** What the child prints before where its cache went. */
    private static final String REPORT_PREFIX = "cache ";
    /** The key of skia_prebuilt.conf that names the Skia release the library reports. */
    private static final String SKIA_TAG_KEY = "SKIA_TAG=";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        PipelineComparison.requireSkia();
        // a profile path with non-ASCII characters (a user name), and under jtreg longer than MAX_PATH
        Path localAppData = Path.of(LOCAL_APP_DATA_NAME).toAbsolutePath();
        Files.createDirectories(localAppData);
        // the default cache location is the subject: no sun.java2d.skia.pipelineCache, and not the suite's VM options
        ProcessBuilder processBuilder = ProcessTools.createLimitedTestJavaProcessBuilder(
                PipelineComparison.skiaChildCommand(
                        List.of("-Dsun.java2d.skia.verbose=true", "-cp", System.getProperty("test.class.path")),
                        SkiaPipelineCacheLocationTest.class, "child"));
        processBuilder.environment().put("LOCALAPPDATA", localAppData.toString());
        OutputAnalyzer childOutput = ProcessTools.executeProcess(processBuilder);
        childOutput.shouldHaveExitValue(0);
        System.out.println(childOutput.getStdout());
        System.out.println(childOutput.getStderr());

        Path cacheRoot = localAppData.resolve("JetBrains").resolve("JBR").resolve("SkiaPipelineCache");
        String release = pinnedRelease();
        Path expectedDirectory;
        if (release != null) {
            expectedDirectory = cacheRoot.resolve(release);
        } else {
            // not a JBR source tree: the one release directory, its name unchecked
            try (Stream<Path> entries = Files.exists(cacheRoot) ? Files.list(cacheRoot) : Stream.empty()) {
                expectedDirectory = entries.filter(Files::isDirectory).findFirst().orElse(cacheRoot);
            }
            System.out.println("no skia_prebuilt.conf (not a JBR source tree): the release name is not checked");
        }
        long cacheFiles;
        try (Stream<Path> entries = Files.exists(expectedDirectory) ? Files.list(expectedDirectory) : Stream.empty()) {
            cacheFiles = entries.filter(entry -> entry.toString().endsWith(".bin")).count();
        }
        String reported = childOutput.getStdout().lines().filter(line -> line.startsWith(REPORT_PREFIX)).findFirst().orElse("");
        if (cacheFiles == 0) {
            throw new RuntimeException("no pipeline cache files in " + expectedDirectory + "; the child reported: " + reported);
        }
        System.out.println(cacheFiles + " cache files in " + expectedDirectory);
        System.out.println("PASSED");
    }

    /** SKIA_TAG of skia_prebuilt.conf, the release the library reports; null outside a JBR source tree. */
    private static String pinnedRelease() throws Exception {
        Path conf = Path.of(System.getProperty("test.root"), "..", "..", "jb", "project", "tools", "common",
                            "skia_prebuilt.conf");
        if (!Files.exists(conf)) {
            return null;
        }
        for (String line : Files.readAllLines(conf)) {
            if (line.startsWith(SKIA_TAG_KEY)) {
                return line.substring(SKIA_TAG_KEY.length()).trim();
            }
        }
        throw new RuntimeException("no SKIA_TAG in " + conf);
    }

    /** Draws something that needs pipelines, waits for it, and reports where the cache went. */
    private static void child() {
        PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), IMAGE_SIZE, IMAGE_SIZE, graphics -> {
            graphics.setColor(Color.RED);
            graphics.fillOval(4, 4, 40, 40);
            graphics.setColor(new Color(0, 0, 255, 128));
            graphics.drawString("cache", 8, 32);
        });
        long[] counters = SKPipelineCache.counters();
        System.out.println(REPORT_PREFIX + SKEnvironment.runningOn()
                           + ": loads " + counters[PipelineComparison.PipelineCacheCounter.LOADS]
                           + ", stores " + counters[PipelineComparison.PipelineCacheCounter.STORES]
                           + ", user.home " + System.getProperty("user.home") + File.separator + ".java");
    }
}
