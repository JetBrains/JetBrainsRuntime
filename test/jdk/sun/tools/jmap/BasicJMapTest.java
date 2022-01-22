/*
 * Copyright (c) 2005, 2021, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.testlibrary.Asserts.assertTrue;
import static jdk.testlibrary.Asserts.fail;

import java.io.File;
import java.util.Arrays;

import jdk.test.lib.hprof.HprofParser;
import jdk.testlibrary.JDKToolLauncher;
import jdk.testlibrary.OutputAnalyzer;
import jdk.testlibrary.ProcessTools;

/*
 * @test
 * @summary Unit test for jmap utility
 * @key intermittent
 * @library /lib/testlibrary
 * @library /test/lib
 * @build jdk.testlibrary.*
 * @build jdk.test.lib.hprof.*
 * @build jdk.test.lib.hprof.model.*
 * @build jdk.test.lib.hprof.parser.*
 * @build jdk.test.lib.hprof.util.*
 * @run main/timeout=240 BasicJMapTest
 */
public class BasicJMapTest {

    private static ProcessBuilder processBuilder = new ProcessBuilder();

    public static void main(String[] args) throws Exception {
        testHisto();
        testHistoLive();
        testFinalizerInfo();
        testClstats();
        testDump();
        testDumpLive();
    }

    private static void testHisto() throws Exception {
        OutputAnalyzer output = jmap("-histo");
        output.shouldHaveExitValue(0);
    }

    private static void testHistoLive() throws Exception {
        OutputAnalyzer output = jmap("-histo:live");
        output.shouldHaveExitValue(0);
    }

    private static void testHistoParallelZero() throws Exception {
        OutputAnalyzer output = jmap("-histo:parallel=0");
        output.shouldHaveExitValue(0);
    }

    private static void testHistoParallel() throws Exception {
        OutputAnalyzer output = jmap("-histo:parallel=2");
        output.shouldHaveExitValue(0);
    }

    private static void testHistoNonParallel() throws Exception {
        OutputAnalyzer output = jmap("-histo:parallel=1");
        output.shouldHaveExitValue(0);
    }

    private static void testFinalizerInfo() throws Exception {
        OutputAnalyzer output = jmap("-finalizerinfo");
        output.shouldHaveExitValue(0);
    }

    private static void testClstats() throws Exception {
        OutputAnalyzer output = jmap("-clstats");
        output.shouldHaveExitValue(0);
    }

    private static void testDump() throws Exception {
        dump(false);
    }

    private static void testDumpLive() throws Exception {
        dump(true);
    }

    private static void dump(boolean live) throws Exception {
        File dump = new File("jmap.dump." + System.currentTimeMillis() + ".hprof");
        if (dump.exists()) {
            dump.delete();
        }
        OutputAnalyzer output;
        if (live) {
            output = jmap("-dump:live,format=b,file=" + dump.getName());
        } else {
            output = jmap("-dump:format=b,file=" + dump.getName());
        }
        output.shouldHaveExitValue(0);
        output.shouldContain("Heap dump file created");
        verifyDumpFile(dump);
        dump.delete();
    }

    private static void verifyDumpFile(File dump) {
        assertTrue(dump.exists() && dump.isFile(), "Could not create dump file " + dump.getAbsolutePath());
        try {
            HprofParser.parse(dump);
        } catch (Exception e) {
            e.printStackTrace();
            fail("Could not parse dump file " + dump.getAbsolutePath());
        }
    }

    private static OutputAnalyzer jmap(String... toolArgs) throws Exception {
        JDKToolLauncher launcher = JDKToolLauncher.createUsingTestJDK("jmap");
        if (toolArgs != null) {
            for (String toolArg : toolArgs) {
                launcher.addToolArg(toolArg);
            }
        }
        launcher.addToolArg(Long.toString(ProcessTools.getProcessId()));

        processBuilder.command(launcher.getCommand());
        System.out.println(Arrays.toString(processBuilder.command().toArray()).replace(",", ""));
        OutputAnalyzer output = ProcessTools.executeProcess(processBuilder);
        System.out.println(output.getOutput());

        return output;
    }

}
