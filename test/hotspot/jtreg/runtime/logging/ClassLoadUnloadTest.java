/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
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
 * @test ClassLoadUnloadTest
 * @bug 8142506
 * @requires vm.flagless
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /runtime/testlibrary
 * @library classes
 * @build test.Empty
 * @run driver ClassLoadUnloadTest
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClassLoadUnloadTest {
    private static class ClassUnloadTestMain {
        public static void main(String... args) throws Exception {
            String className = "test.Empty";
            ClassLoader cl = ClassUnloadCommon.newClassLoader();
            Class<?> c = cl.loadClass(className);
            cl = null; c = null;
            ClassUnloadCommon.triggerUnloading();
        }
    }

    static void checkFor(OutputAnalyzer output, String... outputStrings) throws Exception {
        for (String s: outputStrings) {
            output.shouldContain(s);
        }
    }

    static void checkAbsent(OutputAnalyzer output, String... outputStrings) throws Exception {
        for (String s: outputStrings) {
            output.shouldNotContain(s);
        }
    }

    // Use the same command-line heap size setting as ../ClassUnload/UnloadTest.java
    static OutputAnalyzer exec(String... args) throws Exception {
        List<String> argsList = new ArrayList<>();
        Collections.addAll(argsList, args);
        Collections.addAll(argsList, "-Xmn8m", "-Dtest.class.path=" + System.getProperty("test.class.path", "."),
                           "-XX:+ClassUnloading", ClassUnloadTestMain.class.getName());
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(argsList);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldHaveExitValue(0);
        return output;
    }

    public static void main(String... args) throws Exception {

        OutputAnalyzer output;

        //  -Xlog:class+unload=info
        output = exec("-Xlog:class+unload=info");
        checkFor(output, "[class,unload]", "unloading class");

        //  -Xlog:class+unload=off
        output = exec("-Xlog:class+unload=off");
        checkAbsent(output,"[class,unload]");

        //  -XX:+TraceClassUnloading
        output = exec("-XX:+TraceClassUnloading");
        checkFor(output, "[class,unload]", "unloading class");

        //  -XX:-TraceClassUnloading
        output = exec("-XX:-TraceClassUnloading");
        checkAbsent(output, "[class,unload]");

        //  -Xlog:class+load=info
        output = exec("-Xlog:class+load=info");
        checkFor(output,"[class,load]", "java.lang.Object", "source:");

        //  -Xlog:class+load=debug
        output = exec("-Xlog:class+load=debug");
        checkFor(output,"[class,load]", "java.lang.Object", "source:", "klass:", "super:", "loader:", "bytes:");

        //  -Xlog:class+load=off
        output = exec("-Xlog:class+load=off");
        checkAbsent(output,"[class,load]");

        //  -XX:+TraceClassLoading
        output = exec("-XX:+TraceClassLoading");
        checkFor(output, "[class,load]", "java.lang.Object", "source:");

        //  -XX:-TraceClassLoading
        output = exec("-XX:-TraceClassLoading");
        checkAbsent(output, "[class,load]");

        //  -verbose:class
        output = exec("-verbose:class");
        checkFor(output,"[class,load]", "java.lang.Object", "source:");
        checkFor(output,"[class,unload]", "unloading class");

        //  -Xlog:class+loader+data=trace
        output = exec("-Xlog:class+loader+data=trace");
        checkFor(output, "[class,loader,data]", "create loader data");

    }
}
