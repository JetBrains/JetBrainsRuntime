/*
 * Copyright 2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * @test
 * @summary Verifies that the HotSpot crash log includes recent OOME
 *          stack traces and some classloader statistics.
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main OOMEStacksInCrashLog
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;


public class OOMEStacksInCrashLog {
    public static void main(String args[]) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            loadClasses();

            System.out.println("Proceeding to crash JVM with OOME");
            crashJVM();
            System.out.println("...shouldn't reach here");
        } else {
            generateAndVerifyCrashLogContents();
        }
    }

    static void crashJVM() {
        System.out.println("------- first attempt to crash -------");
        long[][][] array = new long[100][][];
        for (int i = 0; i < 100; i++) {
            System.out.println("------- crash attempt #" + i + "-------");
            array[i] = new long[1000][1000];
        }
        int random = (int) (Math.random() * 100);

        System.out.println(array[random][42][0]);
        System.out.println(class1);
        System.out.println(class2);
        System.out.println(class3);
    }

    static class MyClassLoader extends ClassLoader {
        public MyClassLoader(ClassLoader parent) {
            super("my test classloader", parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("OOMEStacksInCrashLog")) {
                try (final InputStream classStream = OOMEStacksInCrashLog.class.getClassLoader()
                        .getResourceAsStream("OOMEStacksInCrashLog.class")) {
                    byte[] classBytes = classStream.readAllBytes();
                    final Class<?> c = defineClass(name, classBytes, 0, classBytes.length);
                    resolveClass(c);
                    return c;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                return super.findClass(name);
            }
        }
    }

    static Class<?> class1;
    static Class<?> class2;
    static Class<?> class3;

    static void loadClasses() throws Exception {
        MyClassLoader loader1 = new MyClassLoader(OOMEStacksInCrashLog.class.getClassLoader());
        class1 = loader1.findClass("OOMEStacksInCrashLog");

        MyClassLoader loader2 = new MyClassLoader(OOMEStacksInCrashLog.class.getClassLoader());
        class2 = loader2.findClass("OOMEStacksInCrashLog");

        MyClassLoader loader3 = new MyClassLoader(OOMEStacksInCrashLog.class.getClassLoader());
        class3 = loader3.findClass("OOMEStacksInCrashLog");
    }

    public static void generateAndVerifyCrashLogContents() throws Exception {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        opts.add("-Xmx20m");
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-XX:+CrashOnOutOfMemoryError");
        opts.add("-XX:+ErrorFileToStdout");
        opts.add(OOMEStacksInCrashLog.class.getName());
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.outputTo(System.out);
        output.shouldContain("OOME stack traces (most recent first)");
        output.shouldContain("OOMEStacksInCrashLog.crashJVM()");

        output.shouldContain("Loader bootstrap");
        output.shouldContain("Loader OOMEStacksInCrashLog$MyClassLoader");
        output.shouldContain("Classloader memory used:");
        output.shouldContain("OOMEStacksInCrashLog                                                            : loaded 4 times");
    }
}
