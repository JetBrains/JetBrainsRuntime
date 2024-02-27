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
 * @summary Verifies that the VM.info jcmd includes RSS information.
 * @library /test/lib
 * @run main/othervm RSSInVmInfo
 */

import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.process.OutputAnalyzer;

import java.io.IOException;
import java.util.Optional;

public class RSSInVmInfo {
    public static void main(String args[]) throws Exception {
        long pid = ProcessHandle.current().pid();
        final OutputAnalyzer output = runJCmd(pid);
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

    static OutputAnalyzer runJCmd(long pid) {
        try {
            final String jcmd = JDKToolFinder.getTestJDKTool("jcmd");
            final ProcessBuilder pb = new ProcessBuilder(jcmd, String.valueOf(pid), "VM.info");
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.outputTo(System.out);
            output.shouldHaveExitValue(0);
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Launching jcmd failed", e);
        }
    }
}
