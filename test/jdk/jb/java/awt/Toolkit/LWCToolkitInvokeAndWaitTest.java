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
import java.util.logging.*;

import sun.awt.AWTAccessor;
import sun.awt.AWTThreading;
import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;

import static helper.ToolkitTestHelper.*;

/*
 * @test
 * @summary Tests different scenarios for LWCToolkit.invokeAndWait().
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx java.desktop/sun.awt
 * @run main/othervm -Dsun.lwawt.macosx.LWCToolkit.invokeAndWait.disposeOnEDTFree=true LWCToolkitInvokeAndWaitTest
 * @run main/othervm -Dlog.level.FINER=true -Dsun.lwawt.macosx.LWCToolkit.invokeAndWait.disposeOnEDTFree=true LWCToolkitInvokeAndWaitTest
 * @author Anton Tarasov
 */
@SuppressWarnings("ConstantConditions")
public class LWCToolkitInvokeAndWaitTest {
    // This property is used in {CAccessibility}
    static final int INVOKE_TIMEOUT_SECONDS = Integer.getInteger("sun.lwawt.macosx.CAccessibility.invokeTimeoutSeconds", 1);

    static final Runnable CONSUME_DISPATCHING = () -> FUTURE.completeExceptionally(new Throwable("Unexpected dispatching!"));
    static final TestLogHandler LOG_HANDLER = new TestLogHandler();

    static volatile CountDownLatch EDT_FAST_FREE_LATCH;

    static {
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
        loop(); // init the event threads

        tryRun(() -> {
            Consumer<Class<?>> setLog = cls -> {
                Logger log = LogManager.getLogManager().getLogger(cls.getName());
                log.setUseParentHandlers(false);
                log.addHandler(LOG_HANDLER);
                if (Boolean.getBoolean("log.level.FINER")) {
                    log.setLevel(Level.FINER);
                }
            };
            setLog.accept(AWTThreading.class);
            setLog.accept(LWCToolkit.class);
        });

        Consumer<InvocationEvent> noop = e -> {};

        initTest(LWCToolkitInvokeAndWaitTest.class);

        testCase("InvocationEvent is normally dispatched", () -> test1(
            "",
            noop,
            () -> System.out.println("I'm dispatched")));

        testCase("InvocationEvent is lost", () -> test1(
            "lost",
            noop,
            CONSUME_DISPATCHING));

        EDT_FAST_FREE_LATCH = new CountDownLatch(2);
        testCase("InvocationEvent is lost (EDT becomes fast free)", () -> test1(
            "lost",
            // notify the invocationEvent has been dispatched
            invocationEvent -> EDT_FAST_FREE_LATCH.countDown(),
            CONSUME_DISPATCHING));

        testCase("InvocationEvent is disposed", () -> test1(
            "disposed",
            invocationEvent -> AWTAccessor.getInvocationEventAccessor().dispose(invocationEvent),
            CONSUME_DISPATCHING));

        testCase("InvocationEvent is timed out (delayed before dispatching)", () -> test1(
            "timed out",
            invocationEvent -> sleep(INVOKE_TIMEOUT_SECONDS * 4),
            CONSUME_DISPATCHING));

        testCase("InvocationEvent is timed out (delayed during dispatching)", () -> test1(
            "timed out",
            noop,
            () -> sleep(INVOKE_TIMEOUT_SECONDS * 4)));

        testCase("invokeAndWait is discarded", () -> test2(
            "discarded"));

        testCase("invokeAndWait is passed", LWCToolkitInvokeAndWaitTest::test3);

        System.out.println("Test PASSED");
    }

    static void test1(String expectedInLog,
                      Consumer<InvocationEvent> onBeforeDispatching,
                      Runnable onDispatching)
    {
        EventQueue.invokeLater(() -> subTest1(onBeforeDispatching, onDispatching));

        check(expectedInLog);
    }

    static void subTest1(Consumer<InvocationEvent> onBeforeDispatching, Runnable onDispatching) {
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
            FUTURE.complete(true);
        }));
    }

    static void test2(String expectedInLog) {
        EventQueue.invokeLater(() ->
            //
            // Blocking EDT.
            //
            LWCToolkit.performOnMainThreadAndWait(() -> {
                //
                // The invocation from AppKit should be discarded.
                //
                tryRun(() -> LWCToolkit.invokeAndWait(EMPTY_RUNNABLE, FRAME, INVOKE_TIMEOUT_SECONDS * 4));
                FUTURE.complete(true);
            }));

        check(expectedInLog);
    }

    static void test3() {
        var point = new CountDownLatch(1);

        EventQueue.invokeLater(() -> {
            // We're on EDT, wait for the second invocation to perform on AppKit.
            await(point, INVOKE_TIMEOUT_SECONDS * 2);

            // This should be dispatched in the RunLoop started by LWCToolkit.invokeAndWait from the second invocation.
            LWCToolkit.performOnMainThreadAndWait(() -> FUTURE.complete(true));
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
            if (!FUTURE.get(INVOKE_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test FAILED! (negative result)");
            }
        });

        loop(); // wait for the logging to be printed

        if (!LOG_HANDLER.testContains(expectedInLog)) {
            throw new RuntimeException("Test FAILED! (not found in the log: \"" + expectedInLog + "\")");
        }
    }

    static void loop() {
        tryRun(() -> EventQueue.invokeAndWait(EMPTY_RUNNABLE));
        var latch = new CountDownLatch(1);
        CThreading.executeOnAppKit(latch::countDown);
        tryRun(latch::await);
    }

    static void sleep(int seconds) {
        tryRun(() -> Thread.sleep(seconds * 1000L));
    }

    static class TestLogHandler extends StreamHandler {
        public StringBuilder buffer = new StringBuilder();

        public TestLogHandler() {
            // Use System.out to merge with the test printing.
            super(System.out, new SimpleFormatter());
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            buffer.append(record.getMessage());
            super.publish(record);
            flush();
        }

        public boolean testContains(String str) {
            boolean contains = buffer.toString().contains(str);
            buffer.setLength(0);
            return contains;
        }
    }
}