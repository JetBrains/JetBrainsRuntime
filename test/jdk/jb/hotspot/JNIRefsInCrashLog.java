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
 * @summary Verifies that the HotSpot crash log includes information
 *          about the number of JNI references and their associated
 *          memory consumption.
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main JNIRefsInCrashLog
 */

import jdk.internal.misc.Unsafe;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.util.ArrayList;


public class JNIRefsInCrashLog {
    static final Unsafe unsafe = Unsafe.getUnsafe();

    public static void main(String args[]) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            System.out.println("Proceeding to crash JVM with OOME");
            crashJVM();
            System.out.println("...shouldn't reach here");
        } else {
            generateAndVerifyCrashLogContents();
        }
    }

    static void crashJVM() {
        long[][][] array = new long[Integer.MAX_VALUE][][];
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            array[i] = new long[Integer.MAX_VALUE][Integer.MAX_VALUE];
        }
        int random = (int) (Math.random() * Integer.MAX_VALUE);
        System.out.println(array[random][42][0]);
    }

    public static void generateAndVerifyCrashLogContents() throws Exception {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        opts.add("-Xmx8m");
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-XX:+CrashOnOutOfMemoryError");
        opts.add("-XX:+ErrorFileToStdout");
        opts.add(JNIRefsInCrashLog.class.getName());
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJvm(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.outputTo(System.out);
        output.shouldContain("JNI global refs memory usage:");
    }
}
