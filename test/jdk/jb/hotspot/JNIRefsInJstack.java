/*
 * Copyright 2023 JetBrains s.r.o.
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


/**
 * @test
 * @summary Verifies that jstack includes information on memory
 *          consumed by JNI global/weak references
 * @library /test/lib
 * @run main/othervm JNIRefsInJstack
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.Platform;

import java.io.IOException;

public class JNIRefsInJstack {

    public static void main(String[] args) {

        long pid = ProcessHandle.current().pid();
        final OutputAnalyzer jstackOutput = runJstack(pid);
        jstackOutput.shouldHaveExitValue(0)
                .shouldContain("JNI global refs memory usage:");
    }


    static OutputAnalyzer runJstack(long pid) {
        try {
            final String JSTACK = JDKToolFinder.getTestJDKTool("jstack");
            final ProcessBuilder pb = new ProcessBuilder(JSTACK, String.valueOf(pid));
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.outputTo(System.out);
            output.shouldHaveExitValue(0);
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Launching jstack failed", e);
        }
    }
}