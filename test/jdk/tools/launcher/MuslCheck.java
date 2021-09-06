/*
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

/* @test
 * @summary Verifies that the launcher doesn't unnecessarily set LD_LIBRARY_PATH on
 *          non-musl-based systems.
 * @requires (os.family == "linux")
 * @library /test/lib
 * @run main MuslCheck
 */

import java.util.Map;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

public class MuslCheck extends TestHelper {
    public static void main(String[] args) throws Exception {
        final Map<String, String> envMap = Map.of("_JAVA_LAUNCHER_DEBUG", "true");
        TestResult tr = doExec(envMap, javaCmd, "-help");
        if (!tr.contains("mustsetenv:")) {
            throw new RuntimeException("Test error: the necessary tracing is missing in the output");
        }

        if (!tr.contains("micro seconds to check for the musl compatibility layer for glibc")) {
            throw new RuntimeException("The check for libgcompat seems to be missing");
        }

        if (tr.contains("mustsetenv: TRUE")) {
            throw new RuntimeException("launcher is not supposed to set LD_LIBRARY_PATH");
        }
    }
}
