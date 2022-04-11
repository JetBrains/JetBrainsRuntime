/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;
import sun.awt.AWTThreading;

import static helper.ToolkitTestHelper.*;
import static helper.ToolkitTestHelper.TestCase.*;

/*
 * @test
 * @summary tests that AWTThreading can manage cross EDT/AppKit blocking invocation requests
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx java.desktop/sun.awt
 * @run main AWTThreadingTest
 * @author Anton Tarasov
 */
@SuppressWarnings("ConstantConditions")
public class AWTThreadingTest {
    static final int TIMEOUT_SECONDS = 1;

    static final AtomicInteger ITER_COUNTER = new AtomicInteger();
    static final AtomicBoolean DUMP_STACK = new AtomicBoolean(false);

    static volatile Thread THREAD;

    public static void main(String[] args) {
        DUMP_STACK.set(args.length > 0 && "dumpStack".equals(args[0]));

        initTest(AWTThreadingTest.class);

        testCase().
            withCaption("certain threads superposition").
            withRunnable(AWTThreadingTest::test1, false).
            run();

        testCase().
            withCaption("random threads superposition").
            withRunnable(AWTThreadingTest::test1, false).
            run();

        testCase().
            withCaption("JBR-4362").
            withRunnable(AWTThreadingTest::test2, false).
            withCompletionTimeout(3).
            run();

        System.out.println("Test PASSED");
    }

    static void test1() {
        ITER_COUNTER.set(0);

        var timer = new TestTimer(TIMEOUT_SECONDS * 3, TimeUnit.SECONDS);
        EventQueue.invokeLater(() -> startThread(() ->
            TEST_CASE_RESULT.isDone() ||
            timer.hasFinished()));

        waitTestCaseCompletion(TIMEOUT_SECONDS * 4);

        tryRun(THREAD::join);

        System.out.println(ITER_COUNTER + " iterations passed");
    }

    static void startThread(Supplier<Boolean> shouldExitLoop) {
        THREAD = new Thread(() -> {

            while (!shouldExitLoop.get()) {
                ITER_COUNTER.incrementAndGet();

                var point_1 = new CountDownLatch(1);
                var point_2 = new CountDownLatch(1);
                var point_3 = new CountDownLatch(1);
                var invocations = new CountDownLatch(2);

                //
                // 1. Blocking invocation from AppKit to EDT
                //
                CThreading.executeOnAppKit(() -> {
                    // We're on AppKit, wait for the 2nd invocation to be on the AWTThreading-pool thread.
                    if (TEST_CASE_NUM == 1) await(point_1, TIMEOUT_SECONDS);

                    tryRun(() -> LWCToolkit.invokeAndWait(() -> {
                        // We're being dispatched on EDT.
                        if (TEST_CASE_NUM == 1) point_2.countDown();

                        // Wait for the 2nd invocation to be executed on AppKit.
                        if (TEST_CASE_NUM == 1) await(point_3, TIMEOUT_SECONDS);
                    }, FRAME));

                    invocations.countDown();
                });

                //
                // 2. Blocking invocation from EDT to AppKit
                //
                EventQueue.invokeLater(() -> AWTThreading.executeWaitToolkit(() -> {
                    // We're on the AWTThreading-pool thread.
                    if (TEST_CASE_NUM == 1) point_1.countDown();

                    // Wait for the 1st invocation to start NSRunLoop and be dispatched
                    if (TEST_CASE_NUM == 1) await(point_2, TIMEOUT_SECONDS);

                    // Perform in JavaRunLoopMode to be accepted by NSRunLoop started by LWCToolkit.invokeAndWait.
                    LWCToolkit.performOnMainThreadAndWait(() -> {
                        if (DUMP_STACK.get()) {
                            dumpAllThreads();
                        }
                        // We're being executed on AppKit.
                        if (TEST_CASE_NUM == 1) point_3.countDown();
                    });

                    invocations.countDown();
                }));

                await(invocations, TIMEOUT_SECONDS * 2);
            } // while

            TEST_CASE_RESULT.complete(true);
        });
        THREAD.setDaemon(true);
        THREAD.start();
    }

    static void test2() {
        var invocations = new CountDownLatch(1);
        var invokeAndWaitCompleted = new AtomicBoolean(false);

        var log = new Consumer<String>() {
            public void accept(String msg) {
                System.out.println(msg);
                System.out.flush();
            }
        };

        CThreading.executeOnAppKit(() -> {
            log.accept("executeOnAppKit - entered");

            //
            // It's expected that LWCToolkit.invokeAndWait() does not exit before its invocation completes.
            //
            tryRun(() -> LWCToolkit.invokeAndWait(() -> {
                log.accept("\tinvokeAndWait - entered");

                AWTThreading.executeWaitToolkit(() -> {
                    log.accept("\t\texecuteWaitToolkit - entered");

                    LWCToolkit.performOnMainThreadAndWait(() -> log.accept("\t\t\tperformOnMainThreadAndWait - entered"));

                    log.accept("\t\t\tperformOnMainThreadAndWait - exited");
                });

                invokeAndWaitCompleted.set(true);
                log.accept("\t\texecuteWaitToolkit - exited");
            }, FRAME));

            log.accept("\tinvokeAndWait - exited");

            if (!invokeAndWaitCompleted.get()) {
                TEST_CASE_RESULT.completeExceptionally(new Throwable("Premature exit from invokeAndWait"));
            }

            invocations.countDown();
        });

        await(invocations, TIMEOUT_SECONDS * 2);
        log.accept("executeOnAppKit + await - exited");

        TEST_CASE_RESULT.complete(true);
    }

    static void dumpAllThreads() {
        Thread.getAllStackTraces().keySet().forEach(t -> {
            System.out.printf("%s\t%s\t%d\t%s\n", t.getName(), t.getState(), t.getPriority(), t.isDaemon() ? "Daemon" : "Normal");
            Arrays.asList(t.getStackTrace()).forEach(frame -> System.out.println("\tat " + frame));
        });
        System.out.println("\n\n");
    }
}