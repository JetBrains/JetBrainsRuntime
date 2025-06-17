/*
 * Copyright (c) 2016, 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @key headful
 * @bug 8156121 8200313
 * @summary "Fail forward" fails for GTK3 if no GTK2 available
 * @library /test/lib
 * @run main JavaVersionTest
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.StringArrayUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class JavaVersionTest {

    public static void main(String[] args) throws Exception {
        test();
    }

    private static void test() throws Exception {

        String args[] = StringArrayUtils.concat(
                "-version");

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(args);
        Process p = ProcessTools.startProcess("Java Version test", pb);
        p.waitFor();

        boolean status = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(line);
                if (line.contains("Runtime Environment")) {
                    status = true;
                }
            }
            if (!status) throw new RuntimeException("Failed");
        }
    }

}
