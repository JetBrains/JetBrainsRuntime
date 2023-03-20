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
 * @summary Verifies that jstack interrupts a thread, which name
 *          is given in the -Djstack.interrupt.thread option
 * @library /test/lib
 * @run main ThreadInterrupt
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.Platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ThreadInterrupt {
    final static String THREAD_NAME = "unique-thread";
    final static String MAGIC_STRING = "---thread " + THREAD_NAME + " has been interrupted---";
    final static String MAGIC_DUMP_STRING = "pretend coroutine dump text from " + ThreadInterrupt.class.getName();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            System.out.println("Test started, pid=" + ProcessHandle.current().pid());
            final Thread t = startThreadWithSpecialName();
            System.out.println("Test has spawned the thread to be interrupted: " + THREAD_NAME);
            t.join();
        } else {
            testThreadInterrupt();
            testFieldsAreRead();
        }
    }

    private static volatile String coroutineDump = "";
    private static volatile long coroutineDumpTime = 0L;
    static Thread startThreadWithSpecialName() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Thread " + THREAD_NAME + " has started");
                coroutineDump = MAGIC_DUMP_STRING;
                coroutineDumpTime = System.nanoTime();
                try {
                    final Object monitor = new Object();
                    synchronized (monitor) {
                        monitor.wait(); // will be interrupted externally by jstack
                    }
                } catch (InterruptedException ignore) {}
                System.out.println(MAGIC_STRING);
                System.out.println("Thread " + THREAD_NAME + " is about to exit");
            }
        }
        );
        t.setName(THREAD_NAME);
        t.start();
        return t;
    }

    static void testThreadInterrupt() throws Exception {
        System.out.println("Verifying if jstack interrupts a thread by the name '" + THREAD_NAME + "' in a child JVM");

        final Process p = runThisTestInAnotherVM("-Djstack.interrupt.thread=" + THREAD_NAME);
        Thread.sleep(500); // give time for the special thread to start
        final OutputAnalyzer jstackOutput = runJstack(String.valueOf(p.pid()));
        Thread.sleep(500); // give time for the special thread to notice interruption
        final OutputAnalyzer output = new OutputAnalyzer(p);
        output.outputTo(System.out);
        output.shouldContain(MAGIC_STRING);
        jstackOutput.shouldNotContain(MAGIC_DUMP_STRING);
    }

    static void testFieldsAreRead() throws Exception {
        System.out.println("Verifying if jstack interrupts a thread and reads "
                + ThreadInterrupt.class.getName()
                + ".coroutineDump field from a child JVM");

        final Process p = runThisTestInAnotherVM(
                "-Djstack.interrupt.thread=" + THREAD_NAME,
                "-Djstack.coroutine.dump.class=" + ThreadInterrupt.class.getName());
        Thread.sleep(500); // give time for the special thread to start
        final OutputAnalyzer jstackOutput = runJstack(String.valueOf(p.pid()));
        Thread.sleep(500); // give time for the special thread to notice interruption
        final OutputAnalyzer output = new OutputAnalyzer(p);
        output.outputTo(System.out);
        output.shouldContain(MAGIC_STRING);
        jstackOutput.shouldContain(MAGIC_DUMP_STRING);
    }

    static Process runThisTestInAnotherVM(String... vmargs) throws IOException {
        final ArrayList<String> opts = new ArrayList<>(List.of(vmargs));
        opts.add(ThreadInterrupt.class.getName());
        opts.add("--test");
        return ProcessTools.createTestJvm(opts).start();
    }

    static OutputAnalyzer runJstack(String pid) {
        try {
            final String JSTACK = JDKToolFinder.getTestJDKTool("jstack");
            final ProcessBuilder pb = new ProcessBuilder(JSTACK, pid);
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.outputTo(System.out);
            output.shouldHaveExitValue(0);
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Launching jstack failed", e);
        }
    }
}
