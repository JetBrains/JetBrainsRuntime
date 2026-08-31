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
 * @summary JBR-9098: A class loaded from a dynamic archive after its JAR is
 *          relocated must get a CodeSource referring to the JAR's new location.
 * @requires vm.cds
 * @library /test/lib /runtime/cds/appcds
 * @compile mypackage/CodeSourceChecker.java
 * @run main/othervm RelocatedApplicationDynamicTest
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.nio.file.Files;
import java.nio.file.Path;

public class RelocatedApplicationDynamicTest {

    public static void main(String[] args) throws Exception {
        Path dumpDir = Path.of(".", "dump_dir");
        Path runDir = Path.of(".", "run_dir");

        Files.createDirectory(dumpDir);

        String jarName = "relocated_app";
        String jarFile = JarBuilder.build(jarName, "mypackage/CodeSourceChecker");

        Path dumpJar = dumpDir.resolve(jarName + ".jar");
        Files.move(Path.of(jarFile), dumpJar);

        Path archive = Path.of("app.jsa");
        OutputAnalyzer output = createArchive(archive, dumpJar);
        output.shouldContain("Written dynamic archive 0x");

        Path runJar = moveApplication(dumpDir, runDir, dumpJar);

        output = useArchive(archive, runJar);
        output.shouldContain("mypackage.CodeSourceChecker source: shared objects file (top)");
        output.shouldContain("CodeSource of mypackage.CodeSourceChecker is correct");
    }

    private static Path moveApplication(Path dumpDir, Path runDir, Path dumpJar) throws Exception {
        Path runJar = runDir.resolve(dumpDir.relativize(dumpJar));
        Files.move(dumpDir, runDir);
        if (Files.exists(dumpJar)) {
            throw new RuntimeException("Test error: " + dumpJar + " still exists");
        }
        return runJar;
    }

    private static OutputAnalyzer createArchive(Path archive, Path dumpJar) throws Exception {
        String[] command = {
            "-XX:ArchiveClassesAtExit=" + archive.toString(),
            "-Xshare:auto",
            "-Xlog:cds+dynamic=debug",
            "-cp", dumpJar.toString(),
            "mypackage.CodeSourceChecker",
            "mypackage.CodeSourceChecker", dumpJar.toString()
        };
        OutputAnalyzer result = TestCommon.executeAndLog(
            ProcessTools.createTestJavaProcessBuilder(command), "dump");
        result.shouldHaveExitValue(0);
        return result;
    }

    private static OutputAnalyzer useArchive(Path archive, Path runJar) throws Exception {
        String[] command = {
            "-Xshare:on",
            "-XX:SharedArchiveFile=" + archive.toString(),
            "-Xlog:class+load=info,class+path=info",
            "-cp", runJar.toString(),
            "mypackage.CodeSourceChecker",
            "mypackage.CodeSourceChecker", runJar.toString()
        };
        OutputAnalyzer result = TestCommon.executeAndLog(
            ProcessTools.createTestJavaProcessBuilder(command),
            "use-archive"
        );
        result.shouldHaveExitValue(0);
        return result;
    }
}
