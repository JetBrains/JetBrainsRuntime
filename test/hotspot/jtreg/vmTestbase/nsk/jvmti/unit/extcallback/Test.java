/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, Azul Systems, Inc. All rights reserved.
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
 *
 * @summary Tests subscribing ClassUnload JVMTI extension event after unsubscribing all events.
 * @bug 8286490
 * @requires vm.jvmti
 * @library /vmTestbase
 *          /test/lib
 * @run driver nsk.share.ExtraClassesBuilder loadClass
 * @run main/othervm/native nsk.jvmti.unit.extcallback.Test
 */

package nsk.jvmti.unit.extcallback;

import jdk.test.lib.process.ProcessTools;

public class Test {

    public static void main(String[] args) throws Exception {
        ProcessTools.executeTestJvm("-agentlib:extcallback", Test1.class.getName())
                    .shouldHaveExitValue(0)
                    .shouldContain("callbackClassUnload called");
    }

    public static class Test1 {
        public static void main(String[] args) throws Exception {
            System.out.println("App started");
            {
                var unloader = new nsk.share.ClassUnloader();
                unloader.loadClass("nsk.jvmti.unit.extcallback.Test2", "./bin/loadClass");
                unloader.unloadClass();
                unloader = null;
            }
            System.gc();
            System.out.println("App finished");
        }
    }
}
