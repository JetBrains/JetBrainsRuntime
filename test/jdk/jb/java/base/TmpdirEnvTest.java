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
 * @summary The {@code java.io.tmpdir} property must adhere
 *          to the POSIX {@code TMPDIR} environment variable on Linux
 *          systems.
 *          In instances where {@code TMPDIR} is unset or empty, the
 *          property should revert to a non-empty platform default. Furthermore,
 *          any explicit override of {@code java.io.tmpdir} specified via
 *          the {@code -D} command-line option must maintain precedence over
 *          the {@code TMPDIR} environment variable.
 * bug JBR-10312
 *
 * @requires os.family == "linux"
 * @library /test/lib
 * @run main TmpdirEnvTest
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.util.Map;

public class TmpdirEnvTest {

    private static final String MARKER = "JBR10312_TMPDIR=";
    private static final String CUSTOM_DIR = "/tmp/jbr10312_tmpdir_env";
    private static final String CLI_DIR = "/tmp/jbr10312_cli_override";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "child".equals(args[0])) {
            System.out.println(MARKER + System.getProperty("java.io.tmpdir"));
            return;
        }

        // Case 1: TMPDIR set -> child's java.io.tmpdir must equal CUSTOM_DIR.
        String case1 = runChild(Map.of("TMPDIR", CUSTOM_DIR), null);
        if (!CUSTOM_DIR.equals(case1)) {
            throw new RuntimeException(
                    "Case 1 (TMPDIR=" + CUSTOM_DIR + "): expected java.io.tmpdir="
                            + CUSTOM_DIR + " but got " + case1);
        }

        // Case 2: TMPDIR empty -> fall back to a non-empty platform default.
        String case2 = runChild(Map.of("TMPDIR", ""), null);
        if (case2.isEmpty()) {
            throw new RuntimeException(
                    "Case 2 (TMPDIR=\"\"): java.io.tmpdir must not be empty");
        }

        // Case 3: TMPDIR removed -> fall back to a non-empty platform default.
        String case3 = runChild(null, null);
        if (case3.isEmpty()) {
            throw new RuntimeException(
                    "Case 3 (TMPDIR unset): java.io.tmpdir must not be empty");
        }

        // Case 4: -Djava.io.tmpdir on command line wins over TMPDIR.
        String case4 = runChild(Map.of("TMPDIR", CUSTOM_DIR),
                                "-Djava.io.tmpdir=" + CLI_DIR);
        if (!CLI_DIR.equals(case4)) {
            throw new RuntimeException(
                    "Case 4 (-Djava.io.tmpdir=" + CLI_DIR + " + TMPDIR=" + CUSTOM_DIR
                            + "): expected " + CLI_DIR + " but got " + case4);
        }
    }

    /**
     * Runs a child JVM that prints {@code java.io.tmpdir} and returns its value.
     *
     * @param envOverride entries to apply on top of the inherited environment
     *                    (after removing TMPDIR); {@code null} leaves TMPDIR removed
     * @param vmOption    a single extra JVM option (e.g., {@code -Djava.io.tmpdir=…}),
     *                    or {@code null} for none
     */
    private static String runChild(Map<String, String> envOverride, String vmOption)
            throws Exception {
        ProcessBuilder pb = vmOption == null
                ? ProcessTools.createTestJavaProcessBuilder("TmpdirEnvTest", "child")
                : ProcessTools.createTestJavaProcessBuilder(vmOption, "TmpdirEnvTest", "child");

        Map<String, String> env = pb.environment();
        env.remove("TMPDIR");
        if (envOverride != null) {
            env.putAll(envOverride);
        }

        OutputAnalyzer oa = ProcessTools.executeProcess(pb).shouldHaveExitValue(0);
        for (String line : oa.asLines()) {
            if (line.startsWith(MARKER)) {
                return line.substring(MARKER.length());
            }
        }
        throw new RuntimeException(
                "Marker line not found in child output:\n"
                        + String.join("\n", oa.asLines()));
    }
}
