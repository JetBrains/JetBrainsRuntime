/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
 * @key headful
 * @bug 8157827
 * @summary AWT_Desktop/Automated/Exceptions/BasicTest loads incorrect GTK
 * version when jdk.gtk.version=3
 * @requires (os.family == "linux")
 * @library /test/lib
 * @run main DesktopGtkLoadTest
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.awt.*;
import java.io.*;

public class DesktopGtkLoadTest {
    public static class RunDesktop {
        public static void main(String[] args) {
            Desktop.getDesktop();
        }
    }

    public static void main(String[] args) throws Exception {
        final ProcessBuilder pbJava = ProcessTools.createTestJavaProcessBuilder(
                "-Djdk.gtk.version=3",
                "-Djdk.gtk.verbose=true",
                RunDesktop.class.getName()
        );
        final OutputAnalyzer output = ProcessTools.executeProcess(pbJava);
        output.shouldNotContain("Looking for GTK2 library");
        output.shouldContain("Looking for GTK3 library");
    }

}
