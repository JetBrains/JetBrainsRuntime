/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

package jdk.test.lib.thread;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import jdk.test.lib.thread.VThreadRunner.ThrowingRunnable;

/**
 * Helper class to allow tests run a task in a virtual thread while pinning its carrier.
 *
 * It defines the {@code runPinned} method to run a task when holding a lock.
 */
public class VThreadPinner {

    /**
     * Thread local with the task to run.
     */
    private static final ThreadLocal<TaskRunner> TASK_RUNNER = new ThreadLocal<>();

    /**
     * Runs a task, capturing any exception or error thrown.
     */
    private static class TaskRunner implements Runnable {
        private final ThrowingRunnable<?> task;
        private Throwable throwable;

        TaskRunner(ThrowingRunnable<?> task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                task.run();
            } catch (Throwable ex) {
                throwable = ex;
            }
        }

        Throwable exception() {
            return throwable;
        }
    }

    /**
     * Runs the given task on a virtual thread pinned to its carrier. If called from a
     * virtual thread then it invokes the task directly.
     */
    public static <X extends Throwable> void runPinned(ThrowingRunnable<X> task) throws X {
        if (!Thread.currentThread().isVirtual()) {
            VThreadRunner.run(() -> runPinned(task));
            return;
        }
        var runner = new TaskRunner(task);
        TASK_RUNNER.set(runner);
        try {
            synchronized (runner) {
                runner.run();
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            TASK_RUNNER.remove();
        }
        Throwable ex = runner.exception();
        if (ex != null) {
            if (ex instanceof RuntimeException e)
                throw e;
            if (ex instanceof Error e)
                throw e;
            throw (X) ex;
        }
    }

}
