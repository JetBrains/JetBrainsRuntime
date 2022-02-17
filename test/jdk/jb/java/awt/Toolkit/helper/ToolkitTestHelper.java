// Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package helper;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ConstantConditions")
public class ToolkitTestHelper {
    public static final Runnable EMPTY_RUNNABLE = () -> {};

    public static volatile CompletableFuture<Boolean> FUTURE;
    public static volatile int TEST_CASE;
    public static volatile JFrame FRAME;

    private static volatile JLabel LABEL;
    private static volatile Thread MAIN_THREAD;

    private static final Runnable UPDATE_LABEL = new Runnable() {
        final Random rand = new Random();
        @Override
        public void run() {
            LABEL.setForeground(new Color(rand.nextFloat(), 0, rand.nextFloat()));
            LABEL.setText("(" + TEST_CASE + ")");
        }
    };

    public static void initTest(Class<?> testClass) {
        MAIN_THREAD = Thread.currentThread();

        assert MAIN_THREAD.getName().toLowerCase().contains("main");

        Thread.UncaughtExceptionHandler handler = MAIN_THREAD.getUncaughtExceptionHandler();
        MAIN_THREAD.setUncaughtExceptionHandler((t, e) -> {
            if (FRAME != null) FRAME.dispose();
            handler.uncaughtException(t, e);
        });

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
            FRAME.setVisible(true);
        }));

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

    public static void testCase(String caseCaption, Runnable test) {
        FUTURE = new CompletableFuture<>();
        FUTURE.whenComplete((r, e) -> Optional.of(e).ifPresent(Throwable::printStackTrace));

        //noinspection NonAtomicOperationOnVolatileField
        String prefix = "(" + (++TEST_CASE) + ")";

        System.out.println("\n" + prefix + " TEST: " + caseCaption);
        UPDATE_LABEL.run();

        test.run();

        System.out.println(prefix + " SUCCEEDED\n");
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
                FUTURE.completeExceptionally(e);
            }
        }
        return defValue;
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
}
