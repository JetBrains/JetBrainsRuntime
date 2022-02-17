// Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package helper;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("ConstantConditions")
public class ToolkitTestHelper {
    public static volatile CompletableFuture<Boolean> FUTURE;
    public static volatile int TEST_CASE;
    public static volatile JFrame FRAME;

    private static volatile Thread MAIN_THREAD;

    public static void initTest(Class<?> testClass, Thread mainThread) {
        assert mainThread.getName().toLowerCase().contains("main");

        MAIN_THREAD = mainThread;
        Thread.UncaughtExceptionHandler handler = mainThread.getUncaughtExceptionHandler();
        mainThread.setUncaughtExceptionHandler((t, e) -> {
            if (FRAME != null) FRAME.dispose();
            handler.uncaughtException(t, e);
        });

        trycatch(() -> EventQueue.invokeAndWait(() -> {
            FRAME = new JFrame(testClass.getSimpleName());
            FRAME.getContentPane().setBackground(Color.green);
            FRAME.setLocationRelativeTo(null);
            FRAME.setSize(200, 200);
            FRAME.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            FRAME.setVisible(true);
        }));
    }

    public static void initTestCase(String caseCaption) {
        FUTURE = new CompletableFuture<>();
        FUTURE.whenComplete((r, e) -> Optional.of(e).ifPresent(Throwable::printStackTrace));

        //noinspection NonAtomicOperationOnVolatileField
        System.out.println("\n(" + (++TEST_CASE) + ") TEST: " + caseCaption);
    }

    public static void finishTestCase(String message) {
        System.out.println("(" + TEST_CASE + ") SUCCEEDED " + message + "\n");
    }

    public static void trycatch(ThrowableRunnable runnable) {
        trycatchAndReturn(() -> {
            runnable.run();
            return null;
        }, null);
    }

    public static <T> T trycatchAndReturn(Callable<T> callable, T defValue) {
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
}
