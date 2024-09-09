/*
 * Copyright 2024 JetBrains s.r.o.
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

/**
 * @test
 * @summary Verifies the error log does contain events previously
 *          logged with JNU_LOG_EVENT()
 * @library /test/lib
 * @run main/native LogEventTest
 */

import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class LogEventTest {

    public static void main(String args[]) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            System.out.println("Proceeding to crash JVM with a simulated assertion failure");
            String osDependentLibraryFileName = toPlatformLibraryName("LogEventTest");
            String nativePathName = System.getProperty("test.nativepath");
            Path nativePath = Paths.get(nativePathName).resolve(osDependentLibraryFileName);
            System.out.println("Loading library from: " + nativePath);
            System.load(String.valueOf(nativePath));
            logEvent(42);
            crashJVM();
            System.out.println("...shouldn't reach here");
        } else {
            generateAndVerifyCrashLogContents();
        }
    }

    public static void generateAndVerifyCrashLogContents() throws Exception {
        String nativePathSetting = "-Dtest.nativepath=" + System.getProperty("test.nativepath");
        ArrayList<String> opts = new ArrayList<>();
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-XX:+ErrorFileToStdout");
        opts.add("--enable-native-access=ALL-UNNAMED");
        opts.add(nativePathSetting);
        opts.add(LogEventTest.class.getName());
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.outputTo(System.out);
        output.shouldContain("LogEventTest: unique log message");
        output.shouldContain("LogEventTest: 42");
    }

    private static String toPlatformLibraryName(String name) {
        return (Platform.isWindows() ? "" : "lib") + name + "." + Platform.sharedLibraryExt();
    }

    static native void logEvent(int arg);
    static native void crashJVM();
}
