/*
 * Copyright 2022 JetBrains s.r.o.
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
 * @requires (os.family == "mac" | os.family == "linux")
 * @summary Verifies that double-free causes hs_err file to be generated
 *          (it is implied that the underlying malloc() is capable of
 *          detecting this and will abort() in that case).
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 * @run main AbortHandler
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

public class AbortHandler {
    static final String MARKER_TEXT = "SHOULD NOT REACH HERE";
    static final String HS_ERR_FILE_NAME = "hs_err.txt";
    static final Unsafe unsafe = Unsafe.getUnsafe();

    public static void main(String args[]) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            final long addr = unsafe.allocateMemory(42);
            unsafe.freeMemory(addr);
            unsafe.freeMemory(addr);
            unsafe.freeMemory(addr);
            unsafe.freeMemory(addr);
            System.out.println(MARKER_TEXT); // not supposed to get here if libc detected double-free
        } else {
            verifyErrorFileNotCreated(null); // with default options
            verifyErrorFileNotCreated("-Djbr.catch.SIGABRT=false");
            verifyErrorFileNotCreated("-Djbr.catch.SIGABRT");
            verifyErrorFileCreated();    // with the option that enables SIGABRT to be caught
        }
    }

    public static void verifyErrorFileNotCreated(String option) throws Exception {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
            opts.add("-XX:-CreateCoredumpOnCrash");
            if (option != null) {
                opts.add(option);
            }
            opts.add("AbortHandler");
            opts.add("--test");
            ProcessBuilder pb = ProcessTools.createTestJvm(opts);
            if (pb.command().contains("-Djbr.catch.SIGABRT=true")) {
                System.out.println("Test is being executed with -Djbr.catch.SIGABRT=true.");
                System.out.println("Skipping verification that hs_err is not generated.");
                return;
            }
            OutputAnalyzer output = new OutputAnalyzer(pb.start());

            final String hs_err_file = output.firstMatch("# *(\\S*hs_err_pid\\d+\\.log)", 1);
            if (hs_err_file != null) {
                throw new RuntimeException("hs_err_pid file generated with default options");
            }
    }

    public static void verifyErrorFileCreated() throws Exception {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-Djbr.catch.SIGABRT=true");
        opts.add("AbortHandler");
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJvm(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());

        output.shouldNotContain(MARKER_TEXT);

        final String hs_err_file = output.firstMatch("# *(\\S*hs_err_pid\\d+\\.log)", 1);
        if (hs_err_file == null) {
            throw new RuntimeException("Did not find hs_err_pid file in output");
        }

        final Path hsErrPath = Paths.get(hs_err_file);
        if (!Files.exists(hsErrPath)) {
            throw new RuntimeException("hs_err_pid file missing at " + hsErrPath + ".\n");
        }

        System.out.println(hs_err_file + " was created");
    }
}
