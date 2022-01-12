/*
 * Copyright 2000-2022 JetBrains s.r.o.
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
 * @summary regression test for JBR-4169 add the module jdk.javadoc
 * @run main CheckDocLintTest
 */

public class CheckDocLintTest {

    static final String CLASS_NAMES[] = {
            "jdk.javadoc.internal.doclint.DocLint",
            "com.sun.tools.doclint.DocLint"
    };

    public static void main(String[] args) {
        Class<?> klass;
        boolean failed = false;
        for (String klassName : CLASS_NAMES)
            try {
                klass = Class.forName(klassName);
                System.out.println(klass + " exists");
            } catch(ClassNotFoundException e) {
                System.out.println("*** " + klassName + " does not exist");
                failed = true;
            }
        if (failed) {
            throw new RuntimeException("Test failed");
        }
        System.out.println("Test passed");
    }
}
