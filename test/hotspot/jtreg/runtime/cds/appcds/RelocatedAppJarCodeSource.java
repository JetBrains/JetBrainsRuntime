/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

/*
 * @test
 * bug JBR-9098
 * @summary Regression test for JBR-9098. Classes loaded from an archive whose
 *          application has been moved to a different directory must get a
 *          CodeSource that refers to the new location of the JAR file.
 * @requires vm.cds
 * @library /test/lib /runtime/cds/appcds
 * @compile test-classes/RelocatedJarApp.java
 * @compile test-classes/RelocatedJarApp2.java
 * @run driver RelocatedAppJarCodeSource
 */

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.test.lib.cds.CDSTestUtils;

// The archive records the classpath locations that were used when it was created.
// When the application is moved to a different directory, those locations no longer
// exist, but the archive is still usable: validation substitutes the longest common
// prefix of the dump-time paths with the runtime one (see AOTClassLocationConfig).
//
// This test makes sure that the substituted (i.e. the actual runtime) location is
// also what the classes loaded from the archive get as their CodeSource. Before
// JBR-9098, the VM used the stale dump-time location instead: converting it to a
// URL failed, so the CodeSource location was null. Because that failure was not
// remembered, the (on Windows very expensive) file system lookup was then repeated
// for every single class loaded from the archive, which made startup with an archive
// slower than without one.
public class RelocatedAppJarCodeSource {

    private static final Path USER_DIR = Paths.get(CDSTestUtils.getOutputDir());

    public static void main(String[] args) throws Exception {
        testSingleJar();
        testTwoJars();
    }

    // A single JAR file: the simplest form of the problem.
    private static void testSingleJar() throws Exception {
        Path dumpDir = freshDir("single_dump");
        Path runDir = freshDir("single_run");

        String appJar = JarBuilder.build("relocated_app", "RelocatedJarApp");
        Path dumpJar = CDSTestUtils.copyFile(appJar, dumpDir.toString());

        TestCommon.testDump(dumpJar.toString(), TestCommon.list("RelocatedJarApp"));

        Path runJar = moveApplication(dumpDir, runDir, dumpJar);

        run(runJar.toString(), List.of(runJar.toString()),
            "RelocatedJarApp source: shared objects file",
            "CodeSource of RelocatedJarApp is correct");
    }

    // Two JAR files in different subdirectories, moved together. The two locations
    // recorded in the archive share a common prefix, but the part after the prefix
    // differs, so each entry needs its own substitution.
    private static void testTwoJars() throws Exception {
        Path dumpDir = freshDir("two_dump");
        Path runDir = freshDir("two_run");

        String appJar = JarBuilder.build("relocated_app", "RelocatedJarApp");
        String appJar2 = JarBuilder.build("relocated_app2", "RelocatedJarApp2");
        Path dumpJar = CDSTestUtils.copyFile(appJar, dumpDir.resolve("a").toString());
        Path dumpJar2 = CDSTestUtils.copyFile(appJar2, dumpDir.resolve("b").toString());

        TestCommon.testDump(dumpJar + File.pathSeparator + dumpJar2,
                            TestCommon.list("RelocatedJarApp", "RelocatedJarApp2"));

        // Move the directory that contains both JAR files, and not the JAR files
        // themselves: if only one of them were relocated, the dump-time and the
        // runtime classpath would no longer have a matching common prefix, the
        // archive would be rejected, and this test would no longer test anything.
        Path runJar = moveApplication(dumpDir, runDir, dumpJar);
        Path runJar2 = runDir.resolve(dumpDir.relativize(dumpJar2));

        run(runJar + File.pathSeparator + runJar2,
            List.of(runJar.toString(), runJar2.toString()),
            "RelocatedJarApp source: shared objects file",
            "RelocatedJarApp2 source: shared objects file",
            "CodeSource of RelocatedJarApp is correct",
            "CodeSource of RelocatedJarApp2 is correct");
    }

    // Moves the whole application from dumpDir to runDir. Note that this is a move
    // and not a copy: the locations recorded in the archive must be gone, or the
    // test would also pass without the fix. Returns the new location of dumpJar.
    private static Path moveApplication(Path dumpDir, Path runDir, Path dumpJar) throws Exception {
        Path runJar = runDir.resolve(dumpDir.relativize(dumpJar));
        Files.move(dumpDir, runDir);
        if (Files.exists(dumpJar)) {
            throw new RuntimeException("Test error: " + dumpJar + " still exists");
        }
        return runJar;
    }

    private static void run(String classPath, List<String> appArgs,
                            String ... checkMessages) throws Exception {
        List<String> opts = new ArrayList<>(List.of(
            "-Xshare:on",
            "-XX:SharedArchiveFile=" + TestCommon.getCurrentArchiveName(),
            "-cp", classPath,
            "-Xlog:class+load=info,class+path=info",
            "RelocatedJarApp"));
        opts.addAll(appArgs);

        CDSTestUtils.Result result = TestCommon.run(opts.toArray(new String[0]));
        result.assertNormalExit(output -> {
                // The archive must actually be in use -- otherwise the classes would
                // be loaded from the JAR files and would have a correct CodeSource
                // even without the fix.
                for (String s : checkMessages) {
                    output.shouldContain(s);
                }
            });
    }

    private static Path freshDir(String name) throws Exception {
        Path dir = USER_DIR.resolve(name);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        return dir;
    }
}
