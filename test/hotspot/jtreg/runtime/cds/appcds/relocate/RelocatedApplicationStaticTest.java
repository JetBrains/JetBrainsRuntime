/* Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026 JetBrains s.r.o.
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
 * bug JBR-9098
 * @summary JBR-9098: A class loaded from a static archive after its JAR is relocated must get
 *          a CodeSource referring to the JAR's new location.
 * @requires vm.cds
 * @requires vm.flagless
 * @library /test/lib /runtime/cds/appcds
 * @compile mypackage/CodeSourceChecker.java mypackage/Another.java
 * @run driver RelocatedApplicationStaticTest
 */

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class RelocatedApplicationStaticTest {

    public static void main(String[] args) throws Exception {
        testSingleJar();
        testMultipleJars();
    }

    private static void testSingleJar() throws Exception {
        Path dumpDir = Path.of(".", "single_dump");
        Path runDir = Path.of(".", "single_run");

        Files.createDirectory(dumpDir);

        String jarName = "relocated_app";
        String jarFile = JarBuilder.build(jarName, "mypackage/CodeSourceChecker", "mypackage/Another");

        Path dumpJar = dumpDir.resolve(jarName + ".jar");
        Files.move(Path.of(jarFile), dumpJar);

        Path classList = Path.of("single-classlist.txt");
        Path archive = Path.of("single-static.jsa");
        createClassList(
            dumpJar.toString(),
            classList,
            "mypackage.CodeSourceChecker",
            dumpJar.toString()
        );
        createArchive(dumpJar.toString(), classList, archive);

        Path runJar = moveApplication(dumpDir, runDir, dumpJar);

        OutputAnalyzer output = useArchive(
            runJar.toString(),
            archive,
            List.of("mypackage.CodeSourceChecker", runJar.toString())
        );
        output.shouldContain("mypackage.CodeSourceChecker source: shared objects file");
        output.shouldContain("CodeSource of mypackage.CodeSourceChecker is correct");

    }

    private static void testMultipleJars() throws Exception {
        Path dumpDir = Path.of(".", "multiple_dump");
        Path runDir = Path.of(".", "multiple_run");

        Files.createDirectory(dumpDir);

        String jarName = "relocated_app";
        String jarName2 = "relocated_app2";

        String jarFile = JarBuilder.build(jarName, "mypackage/CodeSourceChecker");
        String jarFile2 = JarBuilder.build(jarName2, "mypackage/Another");

        Path dumpJar = dumpDir.resolve(jarName + ".jar");
        Files.move(Path.of(jarFile), dumpJar);

        Path dumpJar2 = dumpDir.resolve(jarName2 + ".jar");
        Files.move(Path.of(jarFile2), dumpJar2);

        String classPath = dumpJar + File.pathSeparator + dumpJar2;
        Path classList = Path.of("multiple-classlist.txt");
        Path archive = Path.of("multiple-static.jsa");
        createClassList(
            classPath,
            classList,
            "mypackage.CodeSourceChecker",
            dumpJar.toString(),
            "mypackage.Another",
            dumpJar2.toString()
        );
        createArchive(classPath, classList, archive);

        Path runJar = moveApplication(dumpDir, runDir, dumpJar);
        Path runJar2 = runDir.resolve(dumpDir.relativize(dumpJar2));

        OutputAnalyzer output = useArchive(
            runJar + File.pathSeparator + runJar2,
            archive,
            List.of("mypackage.CodeSourceChecker", runJar.toString(), "mypackage.Another", runJar2.toString())
        );
        output.shouldContain("mypackage.CodeSourceChecker source: shared objects file");
        output.shouldContain("mypackage.Another source: shared objects file");
        output.shouldContain("CodeSource of mypackage.CodeSourceChecker is correct");
        output.shouldContain("CodeSource of mypackage.Another is correct");
    }

    private static Path moveApplication(Path dumpDir, Path runDir, Path dumpJar) throws Exception {
        Path runJar = runDir.resolve(dumpDir.relativize(dumpJar));
        Files.move(dumpDir, runDir);
        if (Files.exists(dumpJar)) {
            throw new RuntimeException("Test error: " + dumpJar + " still exists");
        }
        return runJar;
    }

    private static void createClassList(String classPath, Path classList,
                                        String... appArgs) throws Exception {
        String[] launchArgs = {
            "-XX:DumpLoadedClassList=" + classList,
            "-cp", classPath,
            "mypackage.CodeSourceChecker"
        };
        List<String> command = new ArrayList<>(List.of(launchArgs));
        command.addAll(List.of(appArgs));
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command.toArray(new String[0]));
        OutputAnalyzer output = TestCommon.executeAndLog(pb, "create-list");
        output.shouldHaveExitValue(0);
    }

    private static void createArchive(String classPath, Path classList,
                                      Path archive) throws Exception {
        String[] launchArgs = {
            "-Xshare:dump",
            "-XX:SharedClassListFile=" + classList,
            "-XX:SharedArchiveFile=" + archive,
            "-cp", classPath
        };
        OutputAnalyzer output = TestCommon.executeAndLog(
            ProcessTools.createTestJavaProcessBuilder(launchArgs), "dump-archive");
        output.shouldHaveExitValue(0);
    }

    private static OutputAnalyzer useArchive(String classPath, Path archive, List<String> appArgs) throws Exception {
         String[] launchArgs = {
             "-Xshare:on",
             "-XX:SharedArchiveFile=" + archive,
             "-cp", classPath,
             "-Xlog:class+load=info,class+path=info",
             "mypackage.CodeSourceChecker"
        };

        List<String> command = new ArrayList<>(List.of(launchArgs));
        command.addAll(appArgs);

        OutputAnalyzer result = TestCommon.executeAndLog(
            ProcessTools.createTestJavaProcessBuilder(command.toArray(new String[0])),
            "use-archive"
        );
        result.shouldHaveExitValue(0);
        return result;
    }
}
