/*
 * Copyright 2025 JetBrains s.r.o.
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

import java.util.concurrent.TimeUnit;

/**
 * @test
 * @bug 8354460
 * @summary <code>JStackHangTest</code> verifies the streaming output of the attach API. It launches
 *       <code>JStackLauncher</code> in a child VM and ensures <code>jstack</code> does not hang.
 * @library /test/lib
 * @compile JStackLauncher.java
 * @run main/othervm JStackHangTest
 */

public class JStackHangTest {

    static final int JSTACK_WAIT_TIME = JStackLauncher.JSTACK_WAIT_TIME * 2;

    public static final int CODE_NOT_RETURNED = 100;

    public static void main(String[] args) throws Exception {

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(JStackLauncher.class.getName());

        Process process = ProcessTools.startProcess("Child", pb);
        OutputAnalyzer outputAnalyzer = new OutputAnalyzer(process);

        int returnCode;

        if (!process.waitFor(JSTACK_WAIT_TIME, TimeUnit.MILLISECONDS)) {
            System.out.println("jstack did not complete in " + JSTACK_WAIT_TIME + " ms.");

            System.out.println("killing all the jstack processes");
            ProcessTools.executeCommand("kill", "-9", Long.toString(process.pid()));
            returnCode = CODE_NOT_RETURNED;

        } else {
            returnCode = outputAnalyzer.getExitValue();
            System.out.println("returnCode = " + returnCode);
        }

        if (returnCode == CODE_NOT_RETURNED)
            throw new RuntimeException("jstack: failed to start or hanged");
        else
            System.out.println("jstack: completed successfully");
    }
}
