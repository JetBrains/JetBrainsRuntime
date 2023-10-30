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

/*
 * @test
 * @summary Verify that C1 compiler does not crash on a particular
 *          Kotlin suspend fun
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @compile Test.java
 * @run main Test
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.util.*;

public class Test {
    public static void main(String[] args) throws Exception {
        String jarFile = System.getProperty("test.src") + "/testcase.jar";

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-cp", jarFile,
                "-XX:+UnlockDiagnosticVMOptions",
                "-Xcomp",
                "-XX:CompileCommand=compileonly,MainKt::test",
                "MainKt");

        new OutputAnalyzer(pb.start())
            .shouldContain("Success")
            .shouldNotContain("Internal Error")
            .shouldHaveExitValue(0);
    }
}

/* 
 * The code in testcase.jar is produced by running 
 *  $ kotlinc Main.kt -include-runtime -d testcase.jar
 * on this Kotlin code:
    suspend fun yield(s: String) { 
        println("Success");
        System.exit(0)
    }

    suspend fun test(s: String) {
        while (true) {
            yield(s)
        }
    }

    suspend fun main() {
        test("")
    }
 */
