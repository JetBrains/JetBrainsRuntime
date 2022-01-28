import java.awt.*;
import javax.swing.*;
import java.awt.event.InvocationEvent;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.*;

import sun.awt.AWTAccessor;
import sun.awt.AWTThreading;
import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;

/*
 * @test
 * @summary Tests different scenarios for LWCToolkit.invokeAndWait().
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx java.desktop/sun.awt
 * @run main LWCToolkitInvokeAndWaitTest
 * @run main/othervm -DAWTThreading.level.FINER=true LWCToolkitInvokeAndWaitTest
 * @author Anton Tarasov
 */
@SuppressWarnings("ConstantConditions")
public class LWCToolkitInvokeAndWaitTest {
    // This property is used in {CAccessibility}
    private static final int INVOKE_TIMEOUT_SECONDS = Integer.getInteger("sun.lwawt.macosx.CAccessibility.invokeTimeoutSeconds", 1);

    static TestLogHandler LOG_HANDLER = new TestLogHandler();
    static volatile CompletableFuture<Boolean> FUTURE;
    static volatile CountDownLatch EDT_FAST_FREE_LATCH;
    static volatile JFrame FRAME;
    static volatile Thread MAIN_THREAD;
    static int TEST_COUNTER;

    static final Runnable CONSUME_DISPATCHING = () -> FUTURE.completeExceptionally(new Throwable("Unexpected dispatching!"));

    static {
        AWTThreading.setAWTThreadingFactory(edt -> new AWTThreading(edt) {
            @Override
            public CompletableFuture<Void> onEventDispatchThreadFree(Runnable runnable) {
                if (EDT_FAST_FREE_LATCH != null) {
                    // 1. wait for the invocation event to be dispatched
                    // 2. wait for EDT to become free
                    trycatch(() -> EDT_FAST_FREE_LATCH.await());

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
        MAIN_THREAD = Thread.currentThread();

        trycatch(() -> {
            Logger log = LogManager.getLogManager().getLogger(AWTThreading.class.getName());
            log.setUseParentHandlers(false);
            log.addHandler(LOG_HANDLER);
            if (Boolean.getBoolean("AWTThreading.level.FINER")) {
                log.setLevel(Level.FINER);
            }
        });

        Consumer<InvocationEvent> noop = e -> {};

        try {
            trycatch(() -> EventQueue.invokeAndWait(LWCToolkitInvokeAndWaitTest::runGui));

            test("InvocationEvent is normally dispatched",
                "",
                noop,
                () -> System.out.println("I'm dispatched"));

            test("InvocationEvent is lost",
                "lost",
                noop,
                CONSUME_DISPATCHING);

            EDT_FAST_FREE_LATCH = new CountDownLatch(2);
            test("InvocationEvent is lost (EDT becomes fast free)",
                "lost",
                // notify the invocationEvent has been dispatched
                invocationEvent -> EDT_FAST_FREE_LATCH.countDown(),
                CONSUME_DISPATCHING);

            test("InvocationEvent is disposed",
                "disposed",
                invocationEvent -> AWTAccessor.getInvocationEventAccessor().dispose(invocationEvent),
                CONSUME_DISPATCHING);

            test("InvocationEvent is timed out (delayed before dispatching)",
                "timed out",
                invocationEvent -> sleep(INVOKE_TIMEOUT_SECONDS * 4),
                CONSUME_DISPATCHING);

            test("InvocationEvent is timed out (delayed during dispatching)",
                "timed out",
                noop,
                () -> sleep(INVOKE_TIMEOUT_SECONDS * 4));

        } finally {
            FRAME.dispose();
        }
        System.out.println("Test PASSED");
    }

    static void runGui() {
        FRAME = new JFrame(LWCToolkitInvokeAndWaitTest.class.getSimpleName());
        FRAME.getContentPane().setBackground(Color.green);
        FRAME.setLocationRelativeTo(null);
        FRAME.setSize(200, 200);
        FRAME.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        FRAME.setVisible(true);
    }

    static void test(String testCaption,
                     String expectedInLog,
                     Consumer<InvocationEvent> onBeforeDispatching,
                     Runnable onDispatching)
    {
        System.out.println("\n(" + (++TEST_COUNTER) + ") TEST: " + testCaption);

        FUTURE = new CompletableFuture<>();
        FUTURE.whenComplete((r, ex) -> Optional.of(ex).ifPresent(Throwable::printStackTrace));

        EventQueue.invokeLater(() -> subTest(onBeforeDispatching, onDispatching));

        trycatch(() -> {
            if (!FUTURE.get(INVOKE_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test FAILED! (negative result)");
            }
        });

        // let AppKit and EDT print all the logging
        var latch = new CountDownLatch(1);
        CThreading.executeOnAppKit(latch::countDown);
        trycatch(latch::await);
        trycatch(() -> EventQueue.invokeAndWait(() -> {}));

        if (!LOG_HANDLER.testContains(expectedInLog)) {
            throw new RuntimeException("Test FAILED! (not found in the log: \"" + expectedInLog + "\")");
        }

        System.out.println("(" + TEST_COUNTER + ") SUCCEEDED\n");
    }

    static void subTest(Consumer<InvocationEvent> onBeforeDispatching, Runnable onDispatching) {
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
        CThreading.executeOnAppKit(() -> trycatch(() -> {
            //
            // Post an invocation from AppKit.
            //
            LWCToolkit.invokeAndWait(onDispatching, FRAME, INVOKE_TIMEOUT_SECONDS);
            FUTURE.complete(true);
        }));
    }

    static void sleep(int seconds) {
        trycatch(() -> Thread.sleep(seconds * 1000L));
    }
    
    static void trycatch(ThrowableRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            if (Thread.currentThread() == MAIN_THREAD) {
                throw new RuntimeException("Test FAILED!", e);
            } else {
                FUTURE.completeExceptionally(e);
            }
        }
    }
    
    interface ThrowableRunnable {
        void run() throws Exception;
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