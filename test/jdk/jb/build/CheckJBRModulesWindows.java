/*
 * Copyright 2022 JetBrains s.r.o.
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

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

/**
 * @test
 * @requires !vm.graal.enabled
 * @requires (os.family == "windows")
 * @summary Verifies the list of available modules specific to Windows
 * @library /test/lib
 * @run main CheckJBRModulesWindows
 */
public class CheckJBRModulesWindows {
    static final String moduleNames[] = { "jdk.crypto.mscapi" };

    public static void main(String args[]) throws Exception {
        final OutputAnalyzer oa = exec("--list-modules").shouldHaveExitValue(0);
        for(String moduleName : moduleNames) {
            oa.shouldContain(moduleName);
        }
    }

    /**
     * java args... returning the OutputAnalyzer to analyzer the output
     */
    private static OutputAnalyzer exec(String... args) throws Exception {
        return ProcessTools.executeTestJava(args)
                .outputTo(System.out)
                .errorTo(System.out);
    }
}
