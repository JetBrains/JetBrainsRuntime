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
 * @summary Regression test for JBR-9098 with a dynamic archive. A class loaded
 *          from the dynamic archive of an application that has been moved to a
 *          different directory must get a CodeSource that refers to the new
 *          location of the JAR file.
 * @requires vm.cds
 * @library /test/lib /test/hotspot/jtreg/runtime/cds/appcds
 * @compile ../test-classes/RelocatedJarApp.java
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. RelocatedAppJarCodeSourceDynamic
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import jdk.test.lib.cds.CDSTestUtils;

// The static archive variant of this test is ../RelocatedAppJarCodeSource.java; see
// there for a description of the problem. This variant uses a dynamic archive, where
// both the base and the top archive are validated at startup, and the classpath
// substitution that makes the moved application usable has to survive both.
public class RelocatedAppJarCodeSourceDynamic extends DynamicArchiveTestBase {

    private static final Path USER_DIR = Paths.get(CDSTestUtils.getOutputDir());

    public static void main(String[] args) throws Exception {
        runTest(RelocatedAppJarCodeSourceDynamic::test);
    }

    static void test() throws Exception {
        String topArchiveName = getNewArchiveName("top");

        Path dumpDir = freshDir("dynamic_dump");
        Path runDir = freshDir("dynamic_run");

        String appJar = JarBuilder.build("relocated_app", "RelocatedJarApp");
        Path dumpJar = CDSTestUtils.copyFile(appJar, dumpDir.toString());

        // The application runs while the dynamic archive is created, so at this point
        // it still checks the CodeSource at its original location.
        dump(topArchiveName,
             "-Xlog:cds+dynamic=debug",   // needed for "Written dynamic archive" below
             "-cp", dumpJar.toString(), "RelocatedJarApp", dumpJar.toString())
            .assertNormalExit(output -> {
                    output.shouldContain("Written dynamic archive 0x");
                });

        // Move the application. This is a move and not a copy: the location recorded
        // in the archive must be gone, or the test would also pass without the fix.
        Path runJar = runDir.resolve(dumpDir.relativize(dumpJar));
        Files.move(dumpDir, runDir);
        if (Files.exists(dumpJar)) {
            throw new RuntimeException("Test error: " + dumpJar + " still exists");
        }

        run(topArchiveName,
            "-Xlog:class+load=info,class+path=info",
            "-cp", runJar.toString(), "RelocatedJarApp", runJar.toString())
            .assertNormalExit(output -> {
                    // "(top)" means the class came from the dynamic archive. If it were
                    // loaded from the JAR file instead, its CodeSource would be correct
                    // even without the fix and this test would not test anything.
                    output.shouldContain("RelocatedJarApp source: shared objects file (top)");
                    output.shouldContain("CodeSource of RelocatedJarApp is correct");
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
