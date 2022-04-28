// Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package helper;

import sun.awt.AWTThreading;
import sun.lwawt.macosx.CThreading;
import sun.lwawt.macosx.LWCToolkit;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.*;

@SuppressWarnings("ConstantConditions")
public class ToolkitTestHelper {
    public static final Runnable EMPTY_RUNNABLE = () -> {};

    public static final TestLogHandler LOG_HANDLER = new TestLogHandler();

    public static volatile CompletableFuture<Boolean> TEST_CASE_RESULT;
    public static volatile int TEST_CASE_NUM;
    public static volatile JFrame FRAME;

    private static volatile JLabel LABEL;
    private static volatile Thread MAIN_THREAD;

    private static final Runnable UPDATE_LABEL = new Runnable() {
        final Random rand = new Random();
        @Override
        public void run() {
            LABEL.setForeground(new Color(rand.nextFloat(), 0, rand.nextFloat()));
            LABEL.setText("(" + TEST_CASE_NUM + ")");
        }
    };

    public static void initTest(Class<?> testClass) {
        MAIN_THREAD = Thread.currentThread();

        assert MAIN_THREAD.getName().toLowerCase().contains("main");

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

        Thread.UncaughtExceptionHandler handler = MAIN_THREAD.getUncaughtExceptionHandler();
        MAIN_THREAD.setUncaughtExceptionHandler((t, e) -> {
            if (FRAME != null) FRAME.dispose();
            handler.uncaughtException(t, e);
        });

        CountDownLatch showLatch = new CountDownLatch(1);
        tryRun(() -> EventQueue.invokeAndWait(() -> {
            FRAME = new JFrame(testClass.getSimpleName());
            LABEL = new JLabel("0");
            LABEL.setFont(LABEL.getFont().deriveFont((float)30));
            LABEL.setHorizontalAlignment(SwingConstants.CENTER);
            FRAME.add(LABEL, BorderLayout.CENTER);
            FRAME.getContentPane().setBackground(Color.green);
            FRAME.setLocationRelativeTo(null);
            FRAME.setSize(200, 200);
            FRAME.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            FRAME.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentShown(ComponentEvent e) {
                    showLatch.countDown();
                }
            });
            FRAME.setVisible(true);
        }));
        //noinspection ResultOfMethodCallIgnored
        tryRun(() -> showLatch.await(1, TimeUnit.SECONDS));

        Timer timer = new Timer(100, e -> UPDATE_LABEL.run());
        timer.setRepeats(true);
        timer.start();

        new Thread(() -> {
            try {
                MAIN_THREAD.join();
            } catch (InterruptedException ignored) {
            }
            if (FRAME != null) FRAME.dispose();
            timer.stop();
        }).start();
    }

    public static void waitTestCaseCompletion(int seconds) {
        tryRun(() -> {
            if (!TEST_CASE_RESULT.get(seconds, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test FAILED! (negative result)");
            }
        });

        loop(); // wait for the logging to be printed
    }

    public static class TestCase {
        volatile String caption = "";
        volatile Runnable testRunnable = EMPTY_RUNNABLE;
        volatile int completionTimeoutSeconds = -1;
        volatile String expectedInLog = "";
        volatile boolean expectedIncludedInLog = true;

        public static TestCase testCase() {
            return new TestCase();
        }

        public TestCase withCaption(String caption) {
            this.caption = caption;
            return this;
        }

        public TestCase withRunnable(Runnable testRunnable, boolean invokeOnEdt) {
            this.testRunnable = invokeOnEdt ? () -> EventQueue.invokeLater(testRunnable) : testRunnable;
            return this;
        }

        public TestCase withCompletionTimeout(int seconds) {
            this.completionTimeoutSeconds = seconds;
            return this;
        }

        public TestCase withExpectedInLog(String expectedInLog, boolean included) {
            this.expectedInLog = expectedInLog;
            this.expectedIncludedInLog = included;
            return this;
        }

        public void run() {
            TEST_CASE_RESULT = new CompletableFuture<>();
            TEST_CASE_RESULT.whenComplete((r, e) -> Optional.of(e).ifPresent(Throwable::printStackTrace));

            //noinspection NonAtomicOperationOnVolatileField
            String prefix = "(" + (++TEST_CASE_NUM) + ")";

            System.out.println("\n" + prefix + " TEST: " + caption);
            UPDATE_LABEL.run();

            testRunnable.run();

            if (completionTimeoutSeconds >= 0) {
                waitTestCaseCompletion(completionTimeoutSeconds);

                if (expectedIncludedInLog && !LOG_HANDLER.testContains(expectedInLog)) {
                    throw new RuntimeException("Test FAILED! (not found in the log: \"" + expectedInLog + "\")");

                } else if (!expectedIncludedInLog && LOG_HANDLER.testContains(expectedInLog)) {
                    throw new RuntimeException("Test FAILED! (found in the log: \"" + expectedInLog + "\")");
                }
            }

            System.out.println(prefix + " SUCCEEDED\n");
        }
    }

    public static void tryRun(ThrowableRunnable runnable) {
        tryCall(() -> {
            runnable.run();
            return null;
        }, null);
    }

    public static <T> T tryCall(Callable<T> callable, T defValue) {
        try {
            return callable.call();
        } catch (Exception e) {
            if (Thread.currentThread() == MAIN_THREAD) {
                throw new RuntimeException("Test FAILED!", e);
            } else {
                TEST_CASE_RESULT.completeExceptionally(e);
            }
        }
        return defValue;
    }

    public static void await(CountDownLatch latch, int seconds) {
        if (!tryCall(() -> latch.await(seconds, TimeUnit.SECONDS), false)) {
            TEST_CASE_RESULT.completeExceptionally(new Throwable("Awaiting has timed out"));
        }
    }

    public static void sleep(int seconds) {
        tryRun(() -> Thread.sleep(seconds * 1000L));
    }

    private static void loop() {
        tryRun(() -> EventQueue.invokeAndWait(EMPTY_RUNNABLE));
        var latch = new CountDownLatch(1);
        CThreading.executeOnAppKit(latch::countDown);
        tryRun(latch::await);
    }

    public interface ThrowableRunnable {
        void run() throws Exception;
    }

    public static class TestTimer {
        private final long finishTime;

        public TestTimer(long timeFromNow, TimeUnit unit) {
            finishTime = System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(timeFromNow, unit);
        }

        public boolean hasFinished() {
            return System.currentTimeMillis() >= finishTime;
        }
    }

    public static class TestLogHandler extends StreamHandler {
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
