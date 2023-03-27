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
import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.Utils;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.util.ArrayList;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

public class JNIRefsInCrashLog {
    static final String HS_ERR_FILE_NAME = "hs_err_42.txt";
    static final Unsafe unsafe = Unsafe.getUnsafe();

    public static void main(String args[]) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            unsafe.putAddress(0, 42);
        } else {
            generateAndVerifyCrashLogContents();
        }
    }


    public static void generateAndVerifyCrashLogContents() throws Exception {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-XX:ErrorFile=./" + HS_ERR_FILE_NAME);
        opts.add(JNIRefsInCrashLog.class.getName());
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJvm(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldContain(HS_ERR_FILE_NAME);

        final Path hsErrPath = Paths.get(HS_ERR_FILE_NAME);
        if (!Files.exists(hsErrPath)) {
            throw new RuntimeException(HS_ERR_FILE_NAME + " file missing");
        }

        final String hsErrorFile = new String(Files.readAllBytes(hsErrPath));
        System.out.println(hsErrorFile);

        if (!hsErrorFile.contains("JNI global refs memory usage:")) {
            throw new RuntimeException("JNI global refs memory usage missing from " + HS_ERR_FILE_NAME);
        }
    }
}
