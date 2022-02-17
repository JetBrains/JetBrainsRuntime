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
 * @summary Verifies the list of available modules
 * @library /test/lib
 * @run main CheckJBRModules
 */
public class CheckJBRModules {
    static final String moduleNames[] = {
                    "java.base",
                    "java.compiler",
                    "java.datatransfer",
                    "java.desktop",
                    "java.instrument",
                    "java.logging",
                    "java.management",
                    "java.management.rmi",
                    "java.naming",
                    "java.net.http",
                    "java.prefs",
                    "java.rmi",
                    "java.scripting",
                    "java.se",
                    "java.security.jgss",
                    "java.security.sasl",
                    "java.smartcardio",
                    "java.sql",
                    "java.sql.rowset",
                    "java.transaction.xa",
                    "java.xml",
                    "java.xml.crypto",
                    "jdk.accessibility",
                    "jdk.attach",
                    "jdk.charsets",
                    "jdk.compiler",
                    "jdk.crypto.cryptoki",
                    "jdk.crypto.ec",
                    "jdk.dynalink",
                    "jdk.httpserver",
                    "jdk.internal.ed",
                    "jdk.internal.le",
                    "jdk.internal.vm.ci",
                    "jdk.javadoc",
                    "jdk.jdi",
                    "jdk.jdwp.agent",
                    "jdk.jfr",
                    "jdk.jsobject",
                    "jdk.localedata",
                    "jdk.management",
                    "jdk.management.agent",
                    "jdk.management.jfr",
                    "jdk.naming.dns",
                    "jdk.naming.rmi",
                    "jdk.net",
                    "jdk.sctp",
                    "jdk.security.auth",
                    "jdk.security.jgss",
                    "jdk.unsupported",
                    "jdk.unsupported.desktop",
                    "jdk.xml.dom",
                    "jdk.zipfs",
                    "jdk.hotspot.agent",
                    "jdk.jcmd" };

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
