/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 * @summary Enhanced class redefinition on live objects with compact object headers
 * @requires vm.jvmti & vm.bits == "64"
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.instrument
 *          jdk.jartool/sun.tools.jar
 * @run main RedefineClassHelper
 * @run main/othervm/timeout=180
 *      -javaagent:redefineagent.jar
 *      -XX:+AllowEnhancedClassRedefinition
 *      -XX:+UseCompactObjectHeaders
 *      -Xbatch
 *      -XX:TieredStopAtLevel=1
 *      -XX:CompileCommand=compileonly,RedefineObjectWithCompactHeaders_B::value
 *      RedefineObjectWithCompactHeaders
 */

import static jdk.test.lib.Asserts.assertEQ;

class RedefineObjectWithCompactHeaders_B {
    int x;

    RedefineObjectWithCompactHeaders_B(int x) {
        this.x = x;
    }

    int value() {
        return x;
    }
}

public class RedefineObjectWithCompactHeaders {
    private static volatile int sink;

    private static final String NEW_CLASS = """
            class RedefineObjectWithCompactHeaders_B {
                int x;
                int y;

                RedefineObjectWithCompactHeaders_B(int x) {
                    this.x = x;
                    this.y = x;
                }

                int value() {
                    return x + y + 1;
                }
            }
            """;

    private static void warmUp(RedefineObjectWithCompactHeaders_B obj, int expected) {
        for (int i = 0; i < 20_000; i++) {
            int value = obj.value();
            if (value != expected) {
                throw new AssertionError("unexpected value: " + value + " expected: " + expected);
            }
            sink ^= value;
        }
    }

    public static void main(String[] args) throws Exception {
        RedefineObjectWithCompactHeaders_B obj = new RedefineObjectWithCompactHeaders_B(7);
        int hashBefore = System.identityHashCode(obj);

        warmUp(obj, 7);

        RedefineClassHelper.redefineClass(RedefineObjectWithCompactHeaders_B.class, NEW_CLASS);

        System.gc();
        warmUp(obj, 8);
        assertEQ(System.identityHashCode(obj), hashBefore, "identity hash changed after redefine");

        RedefineObjectWithCompactHeaders_B fresh = new RedefineObjectWithCompactHeaders_B(7);
        warmUp(fresh, 15);
    }
}
