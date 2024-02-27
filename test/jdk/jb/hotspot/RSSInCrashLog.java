/*
 * Copyright 2024 JetBrains s.r.o.
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

/*
 * @test
 * @summary Verifies that the HotSpot crash log includes RSS information.
 * @library /test/lib
 * @run main RSSInCrashLog
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

import java.util.ArrayList;
import java.util.Optional;

public class RSSInCrashLog {
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
        System.out.println("------- first attempt to crash -------");
        long[][][] array = new long[100][][];
        for (int i = 0; i < 100; i++) {
            System.out.println("------- crash attempt #" + i + "-------");
            array[i] = new long[1000][1000];
        }
        int random = (int) (Math.random() * 100);

        System.out.println(array[random][42][0]);
    }

    public static void generateAndVerifyCrashLogContents() throws Exception {
        ArrayList<String> opts = new ArrayList<>();
        opts.add("-Xmx20m");
        opts.add("-XX:-CreateCoredumpOnCrash");
        opts.add("-XX:+CrashOnOutOfMemoryError");
        opts.add("-XX:+ErrorFileToStdout");
        opts.add(RSSInCrashLog.class.getName());
        opts.add("--test");
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(opts);
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.outputTo(System.out);
        output.shouldContain("Process memory usage");
        output.shouldContain("Resident Set Size: ");

        Optional<String> rssLine = output.asLines().stream().filter(it -> it.startsWith("Resident Set Size:")).findAny();
        String rssValue = rssLine.get().split(" ")[3];
        if (!rssValue.endsWith("K")) {
            throw new RuntimeException("RSS '" + rssValue + "' does not end with 'K'");
        }
        rssValue = rssValue.substring(0, rssValue.length() - 1);
        long rss = Long.parseLong(rssValue);
        System.out.println("Parsed RSS: " + rss);
        if (rss < 10) {
            throw new RuntimeException("RSS is unusually small: " + rss + "K");
        }
    }
}
