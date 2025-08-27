/*
 * Copyright 2025 JetBrains s.r.o.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * <code>JStackLauncher</code> is used to launch <code>jstack</code> in the test of streaming output for the attach API
 *
 */

public class JStackLauncher {

    public static final String JAVA_HOME = System.getProperty("java.home");
    public static final String JAVA_BIN = JAVA_HOME + File.separator + "bin";
    public static final String JSTACK_PATH = JAVA_BIN + File.separator + "jstack";

    static final int JSTACK_WAIT_TIME = 5000;

    static final int THREAD_COUNT = 10;
    static final int STACK_DEPTH = 100;

    static Process process;
    static CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

    public static void main(String[] args) throws InterruptedException {
        // Create threads with deep stacks
        List<Thread> threads = createManyThreadsWithDeepStacks();

        // Run jstack against current process
        System.out.println("Starting jstack...");
        try {
            process = new ProcessBuilder(JSTACK_PATH, String.valueOf(ProcessHandle.current().pid())).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Reading jstack output...");
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Stream<String> output = reader.lines();
        output.forEach(System.out::println);

        if (!process.waitFor(JSTACK_WAIT_TIME, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("jstack failed to complete in " + JSTACK_WAIT_TIME + " ms.");
        }

        threads.forEach(Thread::interrupt);
    }

    public static List<Thread> createManyThreadsWithDeepStacks() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
            System.out.println("Creating thread " + threadIndex);

            Thread t = new DeepStackThread(threadIndex);

            t.start();
            threads.add(t);
        }
        latch.await();

        return threads;
    }

    static class DeepStackThread extends Thread {
        DeepStackThread(int index) {
            super.setName("DeepStackThread-" + index);
        }

        @Override
        public synchronized void run() {
            try {
                createDeepStack(STACK_DEPTH);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Thread " + getName() + " exiting");
        }

        void createDeepStack(int depth) throws InterruptedException {
            if (depth > 0) {
                createDeepStack(depth - 1);
            } else {
                latch.countDown();
                Thread.sleep(Long.MAX_VALUE);
            }
        }
    }
}
