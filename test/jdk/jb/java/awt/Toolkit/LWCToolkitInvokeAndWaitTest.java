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
import java.awt.event.InvocationEvent;
import java.util.concurrent.*;
import java.util.function.Consumer;

import sun.awt.AWTAccessor;
import sun.awt.AWTThreading;
import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;

import static helper.ToolkitTestHelper.*;
import static helper.ToolkitTestHelper.TestCase.*;

/*
 * @test
 * @summary Tests different scenarios for LWCToolkit.invokeAndWait().
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx java.desktop/sun.awt
 * @run main LWCToolkitInvokeAndWaitTest
 * @run main/othervm -Dlog.level.FINER=true LWCToolkitInvokeAndWaitTest
 * @author Anton Tarasov
 */
@SuppressWarnings("ConstantConditions")
public class LWCToolkitInvokeAndWaitTest {
    // This property is used in {CAccessibility}
    static final int INVOKE_TIMEOUT_SECONDS = Integer.getInteger("sun.lwawt.macosx.CAccessibility.invokeTimeoutSeconds", 1);

    static final Runnable CONSUME_DISPATCHING = () -> TEST_CASE_RESULT.completeExceptionally(new Throwable("Unexpected dispatching!"));

    static volatile CountDownLatch EDT_FAST_FREE_LATCH;

    static {
        System.setProperty("sun.lwawt.macosx.LWCToolkit.invokeAndWait.disposeOnEDTFree", "true");

        AWTThreading.setAWTThreadingFactory(edt -> new AWTThreading(edt) {
            @Override
            public CompletableFuture<Void> onEventDispatchThreadFree(Runnable runnable) {
                if (EDT_FAST_FREE_LATCH != null) {
                    // 1. wait for the invocation event to be dispatched
                    // 2. wait for EDT to become free
                    tryRun(EDT_FAST_FREE_LATCH::await);

                    EDT_FAST_FREE_LATCH = null;
                }
                // if EDT is free the runnable should be called immediately
                return super.onEventDispatchThreadFree(runnable);
            }
            @Override
            public void notifyEventDispatchThreadFree() {
                if (EDT_FAST_FREE_LATCH != null &&
                    // if the invocation event is dispatched by now
                    EDT_FAST_FREE_LATCH.getCount() == 1)
                {
                    EDT_FAST_FREE_LATCH.countDown();
                }
                super.notifyEventDispatchThreadFree();
            }
        });
    }

    public static void main(String[] args) {
        initTest(LWCToolkitInvokeAndWaitTest.class);

        Consumer<InvocationEvent> noop = e -> {};

        testCase().
            withCaption("InvocationEvent is normally dispatched").
            withRunnable(() -> test1(noop, () -> System.out.println("I'm dispatched")), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            run();

        testCase().
            withCaption("InvocationEvent is lost").
            withRunnable(() -> test1(noop, CONSUME_DISPATCHING), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("lost", true).
            run();

        EDT_FAST_FREE_LATCH = new CountDownLatch(2);
        testCase().
            withCaption("InvocationEvent is lost (EDT becomes fast free)").
            withRunnable(() -> test1(invocationEvent -> EDT_FAST_FREE_LATCH.countDown(), CONSUME_DISPATCHING), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("lost", true).
            run();

        testCase().
            withCaption("InvocationEvent is disposed").
            withRunnable(() -> test1(invocationEvent -> AWTAccessor.getInvocationEventAccessor().dispose(invocationEvent), CONSUME_DISPATCHING), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("disposed", true).
            run();

        testCase().
            withCaption("InvocationEvent is timed out (delayed before dispatching)").
            withRunnable(() -> test1(invocationEvent -> sleep(INVOKE_TIMEOUT_SECONDS * 4), CONSUME_DISPATCHING), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("timed out", true).
            run();

        testCase().
            withCaption("InvocationEvent is timed out (delayed during dispatching)").
            withRunnable(() -> test1(noop, () -> sleep(INVOKE_TIMEOUT_SECONDS * 4)), true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("timed out", true).
            run();

        testCase().
            withCaption("invokeAndWait is discarded").
            withRunnable(LWCToolkitInvokeAndWaitTest::test2, true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            withExpectedInLog("discarded", true).
            run();

        testCase().
            withCaption("invokeAndWait is passed").
            withRunnable(LWCToolkitInvokeAndWaitTest::test3, true).
            withCompletionTimeout(INVOKE_TIMEOUT_SECONDS * 2).
            run();

        System.out.println("Test PASSED");
    }

    static void test1(Consumer<InvocationEvent> onBeforeDispatching, Runnable onDispatching) {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            protected void dispatchEvent(AWTEvent event) {
                //
                // Intercept the invocation posted from Appkit.
                //
                if (event instanceof AWTThreading.TrackedInvocationEvent) {
                    System.out.println("Before dispatching: " + event);
                    onBeforeDispatching.accept((InvocationEvent)event);

                    if (onDispatching == CONSUME_DISPATCHING) {
                        System.out.println("Consuming: " + event);
                        return;
                    }
                }
                super.dispatchEvent(event);
            }
        });
        CThreading.executeOnAppKit(() -> tryRun(() -> {
            //
            // Post an invocation from AppKit.
            //
            LWCToolkit.invokeAndWait(onDispatching, FRAME, INVOKE_TIMEOUT_SECONDS);
            TEST_CASE_RESULT.complete(true);
        }));
    }

    static void test2() {
        EventQueue.invokeLater(() ->
            //
            // Blocking EDT.
            //
            LWCToolkit.performOnMainThreadAndWait(() -> {
                //
                // The invocation from AppKit should be discarded.
                //
                tryRun(() -> LWCToolkit.invokeAndWait(EMPTY_RUNNABLE, FRAME, INVOKE_TIMEOUT_SECONDS * 4));
                TEST_CASE_RESULT.complete(true);
            }));
    }

    static void test3() {
        var point = new CountDownLatch(1);

        EventQueue.invokeLater(() -> {
            // We're on EDT, wait for the second invocation to perform on AppKit.
            await(point, INVOKE_TIMEOUT_SECONDS * 2);

            // This should be dispatched in the RunLoop started by LWCToolkit.invokeAndWait from the second invocation.
            LWCToolkit.performOnMainThreadAndWait(() -> TEST_CASE_RESULT.complete(true));
        });

        LWCToolkit.performOnMainThreadAndWait(() -> {
            // Notify we're on AppKit.
            point.countDown();

            // The LWCToolkit.invokeAndWait call starts a native RunLoop.
            tryRun(() -> LWCToolkit.invokeAndWait(EMPTY_RUNNABLE, FRAME));
        });
    }

    static void check(String expectedInLog) {
        tryRun(() -> {
            if (!TEST_CASE_RESULT.get(INVOKE_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test FAILED! (negative result)");
            }
        });
    }
}