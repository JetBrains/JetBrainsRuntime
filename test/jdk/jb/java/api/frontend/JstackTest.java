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

/*
 * @test
 * @summary Verifies that jstack includes whatever the supplier
 *          provided to Jstack.includeInfoFrom() returns.
 * @library /test/lib
 * @run main JstackTest
 */

import com.jetbrains.JBR;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.Platform;

import java.io.IOException;

public class JstackTest {
    final static String MAGIC_STRING = "Additional info:\nthis appears in jstack's output";

    public static void main(String[] args) {
        JBR.getJstack().includeInfoFrom( () -> MAGIC_STRING );
        long pid = ProcessHandle.current().pid();
        final OutputAnalyzer jstackOutput = runJstack(pid);
        jstackOutput
                .shouldHaveExitValue(0)
                .shouldContain(MAGIC_STRING);
    }

    static OutputAnalyzer runJstack(long pid) {
        try {
            final String JSTACK = JDKToolFinder.getTestJDKTool("jstack");
            final ProcessBuilder pb = new ProcessBuilder(JSTACK, String.valueOf(pid));
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.outputTo(System.out);
            output.shouldHaveExitValue(0);
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Launching jstack failed", e);
        }
    }
}
