/*
 * Copyright 2024 JetBrains s.r.o.
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

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * @summary Verifies that the "soft" limit for the number of open files
 *          does not exceed 10240, an acceptable number that was
 *          the default on macOS for a long time.
 * @library /test/lib
 * @requires os.family == "mac"
 * @run main OpenFilesLimit
 */

public class OpenFilesLimit {
    private static final String ULIMIT_GET_CMD = "ulimit -n";
    private static final int MACOS_DEFAULT_LIMIT = 10240;

    public static void main(String[] args) throws Exception {
        OutputAnalyzer oa = ProcessTools.executeCommand("sh", "-c", ULIMIT_GET_CMD)
                .shouldHaveExitValue(0);
        String s = oa.getStdout().strip();
        System.out.println("ulimit returned :'" + s + "'");
        int i = Integer.parseInt(s);
        System.out.println("ulimit -n is " + i);
        System.out.println("Expecting " + MACOS_DEFAULT_LIMIT + " or less");
        if (i > MACOS_DEFAULT_LIMIT) {
            throw new RuntimeException("ulimit -n returned " + i + " that is higher than expected " + MACOS_DEFAULT_LIMIT);
        }
    }
}