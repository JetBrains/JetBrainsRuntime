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
 * @summary Regression test for JBR-9098 with an AOT cache. A class loaded from the
 *          AOT cache of an application that has been moved to a different directory
 *          must get a CodeSource that refers to the new location of the JAR file.
 * @requires vm.cds.supports.aot.class.linking
 * @requires vm.flagless
 * @library /test/lib /test/hotspot/jtreg/runtime/cds/appcds
 * @compile ../test-classes/RelocatedJarApp.java
 * @run driver RelocatedAppJarCodeSourceAOT
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

// The static archive variant of this test is ../RelocatedAppJarCodeSource.java; see
// there for a description of the problem. This variant uses an AOT cache, which is
// created by the one step training/assembly workflow of -XX:AOTCacheOutput.
public class RelocatedAppJarCodeSourceAOT {

    private static final Path USER_DIR = Paths.get(CDSTestUtils.getOutputDir());

    public static void main(String[] args) throws Exception {
        Path dumpDir = freshDir("aot_dump");
        Path runDir = freshDir("aot_run");
        Path cache = USER_DIR.resolve("relocated_app.aot");

        String appJar = JarBuilder.build("relocated_app", "RelocatedJarApp");
        Path dumpJar = CDSTestUtils.copyFile(appJar, dumpDir.toString());

        // Create the AOT cache. The application runs while the cache is created, so at
        // this point it still checks the CodeSource at its original location.
        execute("aot-create",
                "-XX:AOTCacheOutput=" + cache,
                "-cp", dumpJar.toString(), "RelocatedJarApp", dumpJar.toString());

        // Move the application. This is a move and not a copy: the location recorded
        // in the cache must be gone, or the test would also pass without the fix.
        Path runJar = runDir.resolve(dumpDir.relativize(dumpJar));
        Files.move(dumpDir, runDir);
        if (Files.exists(dumpJar)) {
            throw new RuntimeException("Test error: " + dumpJar + " still exists");
        }

        OutputAnalyzer out = execute("aot-production",
                "-XX:AOTCache=" + cache,
                "-Xlog:class+load=info,class+path=info",
                "-cp", runJar.toString(), "RelocatedJarApp", runJar.toString());

        // The cache must actually be in use -- otherwise the class would be loaded
        // from the JAR file and would have a correct CodeSource even without the fix.
        out.shouldContain("RelocatedJarApp source: shared objects file");
        out.shouldContain("CodeSource of RelocatedJarApp is correct");
    }

    private static OutputAnalyzer execute(String logName, String ... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(args));
        OutputAnalyzer out = CDSTestUtils.executeAndLog(
            ProcessTools.createTestJavaProcessBuilder(cmd), logName);
        out.shouldHaveExitValue(0);
        return out;
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
