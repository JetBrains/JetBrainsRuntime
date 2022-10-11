/*
 * Copyright (c) 2014, 2019, Oracle and/or its affiliates. All rights reserved.
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

package gc.g1;

/*
 * @test TestGCLogMessages
 * @bug 8035406 8027295 8035398 8019342 8027959 8048179 8027962 8069330 8076463 8150630 8160055 8177059 8166191
 * @summary Ensure the output for a minor GC with G1
 * includes the expected necessary messages.
 * @key gc
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @build sun.hotspot.WhiteBox
 * @run driver ClassFileInstaller sun.hotspot.WhiteBox
 * @run main gc.g1.TestGCLogMessages
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.Platform;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.Platform;

public class TestGCLogMessages {

    private enum Level {
        OFF(""),
        INFO("info"),
        DEBUG("debug"),
        TRACE("trace");

        private String logName;

        Level(String logName) {
            this.logName = logName;
        }

        public boolean lessThan(Level other) {
            return this.compareTo(other) < 0;
        }

        public String toString() {
            return logName;
        }
    }

    private class LogMessageWithLevel {
        String message;
        Level level;

        public LogMessageWithLevel(String message, Level level) {
            this.message = message;
            this.level = level;
        }

        public boolean isAvailable() {
            return true;
        }
    };

    private class LogMessageWithLevelC2OrJVMCIOnly extends LogMessageWithLevel {
        public LogMessageWithLevelC2OrJVMCIOnly(String message, Level level) {
            super(message, level);
        }

        public boolean isAvailable() {
            return Platform.isGraal() || Platform.isServer();
        }
    }

    private LogMessageWithLevel allLogMessages[] = new LogMessageWithLevel[] {
        new LogMessageWithLevel("Pre Evacuate Collection Set", Level.INFO),
        new LogMessageWithLevel("Evacuate Collection Set", Level.INFO),
        new LogMessageWithLevel("Post Evacuate Collection Set", Level.INFO),
        new LogMessageWithLevel("Other", Level.INFO),

        // Update RS
        new LogMessageWithLevel("Update RS", Level.DEBUG),
        new LogMessageWithLevel("Processed Buffers", Level.DEBUG),
        new LogMessageWithLevel("Scanned Cards", Level.DEBUG),
        new LogMessageWithLevel("Skipped Cards", Level.DEBUG),
        new LogMessageWithLevel("Scan HCC", Level.TRACE),
        // Scan RS
        new LogMessageWithLevel("Scan RS", Level.DEBUG),
        new LogMessageWithLevel("Scanned Cards", Level.DEBUG),
        new LogMessageWithLevel("Claimed Cards", Level.DEBUG),
        new LogMessageWithLevel("Skipped Cards", Level.DEBUG),
        // Ext Root Scan
        new LogMessageWithLevel("Thread Roots", Level.TRACE),
        new LogMessageWithLevel("StringTable Roots", Level.TRACE),
        new LogMessageWithLevel("Universe Roots", Level.TRACE),
        new LogMessageWithLevel("JNI Handles Roots", Level.TRACE),
        new LogMessageWithLevel("ObjectSynchronizer Roots", Level.TRACE),
        new LogMessageWithLevel("Management Roots", Level.TRACE),
        new LogMessageWithLevel("SystemDictionary Roots", Level.TRACE),
        new LogMessageWithLevel("CLDG Roots", Level.TRACE),
        new LogMessageWithLevel("JVMTI Roots", Level.TRACE),
        new LogMessageWithLevel("SATB Filtering", Level.TRACE),
        new LogMessageWithLevel("CM RefProcessor Roots", Level.TRACE),
        new LogMessageWithLevel("Wait For Strong CLD", Level.TRACE),
        new LogMessageWithLevel("Weak CLD Roots", Level.TRACE),
        // Redirty Cards
        new LogMessageWithLevel("Redirty Cards", Level.DEBUG),
        new LogMessageWithLevel("Parallel Redirty", Level.TRACE),
        new LogMessageWithLevel("Redirtied Cards", Level.TRACE),
        // Misc Top-level
        new LogMessageWithLevel("Code Roots Purge", Level.DEBUG),
        new LogMessageWithLevel("String Dedup Fixup", Level.DEBUG),
        new LogMessageWithLevel("Expand Heap After Collection", Level.DEBUG),
        // Free CSet
        new LogMessageWithLevel("Free Collection Set", Level.DEBUG),
        new LogMessageWithLevel("Free Collection Set Serial", Level.TRACE),
        new LogMessageWithLevel("Young Free Collection Set", Level.TRACE),
        new LogMessageWithLevel("Non-Young Free Collection Set", Level.TRACE),
        // Humongous Eager Reclaim
        new LogMessageWithLevel("Humongous Reclaim", Level.DEBUG),
        new LogMessageWithLevel("Humongous Register", Level.DEBUG),
        // Merge PSS
        new LogMessageWithLevel("Merge Per-Thread State", Level.DEBUG),
        // TLAB handling
        new LogMessageWithLevel("Prepare TLABs", Level.DEBUG),
        new LogMessageWithLevel("Resize TLABs", Level.DEBUG),
        // Reference Processing
        new LogMessageWithLevel("Reference Processing", Level.DEBUG),
        // VM internal reference processing
        new LogMessageWithLevel("Weak Processing", Level.DEBUG),

        new LogMessageWithLevelC2OrJVMCIOnly("DerivedPointerTable Update", Level.DEBUG),
        new LogMessageWithLevel("Start New Collection Set", Level.DEBUG),
    };

    void checkMessagesAtLevel(OutputAnalyzer output, LogMessageWithLevel messages[], Level level) throws Exception {
        for (LogMessageWithLevel l : messages) {
            if (level.lessThan(l.level) || !l.isAvailable()) {
                output.shouldNotContain(l.message);
            } else {
                output.shouldMatch("\\[" + l.level + ".*" + l.message);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new TestGCLogMessages().testNormalLogs();
        if (Platform.isDebugBuild()) {
          new TestGCLogMessages().testWithEvacuationFailureLogs();
        }
        new TestGCLogMessages().testWithInitialMark();
        new TestGCLogMessages().testExpandHeap();
    }

    private void testNormalLogs() throws Exception {

        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                                  "-Xmx10M",
                                                                  GCTest.class.getName());

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        checkMessagesAtLevel(output, allLogMessages, Level.OFF);
        output.shouldHaveExitValue(0);

        pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                   "-XX:+UseStringDeduplication",
                                                   "-Xmx10M",
                                                   "-Xlog:gc+phases=debug",
                                                   GCTest.class.getName());

        output = new OutputAnalyzer(pb.start());
        checkMessagesAtLevel(output, allLogMessages, Level.DEBUG);

        pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                   "-XX:+UseStringDeduplication",
                                                   "-Xmx10M",
                                                   "-Xlog:gc+phases=trace",
                                                   GCTest.class.getName());

        output = new OutputAnalyzer(pb.start());
        checkMessagesAtLevel(output, allLogMessages, Level.TRACE);
        output.shouldHaveExitValue(0);
    }

    LogMessageWithLevel exhFailureMessages[] = new LogMessageWithLevel[] {
        new LogMessageWithLevel("Evacuation Failure", Level.DEBUG),
        new LogMessageWithLevel("Recalculate Used", Level.TRACE),
        new LogMessageWithLevel("Remove Self Forwards", Level.TRACE),
    };

    private void testWithEvacuationFailureLogs() throws Exception {
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                                  "-Xmx32M",
                                                                  "-Xmn16M",
                                                                  "-XX:+G1EvacuationFailureALot",
                                                                  "-XX:G1EvacuationFailureALotCount=100",
                                                                  "-XX:G1EvacuationFailureALotInterval=1",
                                                                  "-Xlog:gc+phases=debug",
                                                                  GCTestWithEvacuationFailure.class.getName());

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        checkMessagesAtLevel(output, exhFailureMessages, Level.DEBUG);
        output.shouldHaveExitValue(0);

        pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                   "-Xmx32M",
                                                   "-Xmn16M",
                                                   "-Xms32M",
                                                   "-Xlog:gc+phases=trace",
                                                   GCTestWithEvacuationFailure.class.getName());

        output = new OutputAnalyzer(pb.start());
        checkMessagesAtLevel(output, exhFailureMessages, Level.TRACE);
        output.shouldHaveExitValue(0);
    }

    private void testWithInitialMark() throws Exception {
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                                  "-Xmx10M",
                                                                  "-Xbootclasspath/a:.",
                                                                  "-Xlog:gc*=debug",
                                                                  "-XX:+UnlockDiagnosticVMOptions",
                                                                  "-XX:+WhiteBoxAPI",
                                                                  GCTestWithInitialMark.class.getName());

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldContain("Clear Claimed Marks");
        output.shouldHaveExitValue(0);
    }

    private void testExpandHeap() throws Exception {
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder("-XX:+UseG1GC",
                                                                  "-Xmx10M",
                                                                  "-Xbootclasspath/a:.",
                                                                  "-Xlog:gc+ergo+heap=debug",
                                                                  "-XX:+UnlockDiagnosticVMOptions",
                                                                  "-XX:+WhiteBoxAPI",
                                                                  GCTest.class.getName());

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldContain("Expand the heap. requested expansion amount: ");
        output.shouldContain("B expansion amount: ");
        output.shouldHaveExitValue(0);
    }


    static class GCTest {
        private static byte[] garbage;
        public static void main(String [] args) {
            System.out.println("Creating garbage");
            // create 128MB of garbage. This should result in at least one GC
            for (int i = 0; i < 1024; i++) {
                garbage = new byte[128 * 1024];
            }
            System.out.println("Done");
        }
    }

    static class GCTestWithEvacuationFailure {
        private static byte[] garbage;
        private static byte[] largeObject;
        private static Object[] holder = new Object[200]; // Must be larger than G1EvacuationFailureALotCount

        public static void main(String [] args) {
            largeObject = new byte[16*1024*1024];
            System.out.println("Creating garbage");
            // Create 16 MB of garbage. This should result in at least one GC,
            // (Heap size is 32M, we use 17MB for the large object above)
            // which is larger than G1EvacuationFailureALotInterval.
            for (int i = 0; i < 16 * 1024; i++) {
                holder[i % holder.length] = new byte[1024];
            }
            System.out.println("Done");
        }
    }

    static class GCTestWithInitialMark {
        public static void main(String [] args) {
            sun.hotspot.WhiteBox WB = sun.hotspot.WhiteBox.getWhiteBox();
            WB.g1StartConcMarkCycle();
        }
    }

}

