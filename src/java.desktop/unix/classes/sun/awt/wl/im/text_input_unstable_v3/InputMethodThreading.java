/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.awt.wl.im.text_input_unstable_v3;

import sun.util.logging.PlatformLogger;

import java.awt.EventQueue;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


final class InputMethodThreading {

    // See java.text.MessageFormat for the formatting syntax
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.InputMethodThreading");


    @FunctionalInterface
    public interface SynchronousStep<R> {
        R execute() throws Exception;
    }

    @FunctionalInterface
    public interface VoidSynchronousStep extends SynchronousStep<Void> {
        default Void execute() throws Exception {
            executeImpl();
            return null;
        }

        void executeImpl() throws Exception;
    }


    public final class ExclusiveAccessContext implements AutoCloseable {
        @Override
        public void close() {
            releaseAccess();
        }

        public void performBlockingOnEdt(VoidSynchronousStep step) throws Exception {
            InputMethodThreading.this.computeBlockingOnEdtUnderInheritedAccess(step);
        }

        public <R> R computeBlockingOnEdt(SynchronousStep<R> step) throws Exception {
            return InputMethodThreading.this.computeBlockingOnEdtUnderInheritedAccess(step);
        }


        /**
         * Workaround for the warning:
         * "[try] auto-closeable resource <...> is never referenced in body of corresponding try statement"
         */
        public void suppressUnusedWarning() {}
    }

    public static final class ExclusiveAccessException extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = -5346229624447010216L;

        public ExclusiveAccessException(Throwable cause) {
            super(cause);
        }
        public ExclusiveAccessException(String message, Throwable cause) {
            super(message, cause);
        }
        public ExclusiveAccessException(String message) {
            super(message);
        }
    }

    // Reentrant
    public ExclusiveAccessContext withExclusiveAccess(long timeoutMilliseconds) throws ExclusiveAccessException {
        try {
            acquireReentrantOrOwningAccess(timeoutMilliseconds);
            try {
                return Objects.requireNonNull(threadAcquiredAccessInfo.get().accessCtxCached, "accessCtxCached");
            } catch (Throwable err) {
                releaseAccess();
                throw err;
            }
        } catch (Throwable err) {
            if (err instanceof ExclusiveAccessException eaErr) {
                throw eaErr;
            } else {
                throw new ExclusiveAccessException(err);
            }
        }
    }


    public static void invokeOnEdtNowOrLater(final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable");

        final boolean isFromEdt = EventQueue.isDispatchThread();

        final Throwable stacktrace = isFromEdt ? null : new Throwable("Invoked from here");
        final Runnable wrapped = () -> {
            try {
                runnable.run();
            } catch (Throwable unexpected) {
                if (stacktrace != null) {
                    unexpected.addSuppressed(stacktrace);
                }

                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning(
                        String.format("invokeOnEdtNowOrLater: unexpected exception on EDT from the following runnable: %s.",
                                      runnable),
                        unexpected
                    );
                }

                throw unexpected;
            }
        };

        if (EventQueue.isDispatchThread()) {
            wrapped.run();
        } else {
            EventQueue.invokeLater(wrapped);
        }
    }


    /**
     * @see #withExclusiveAccess
     */
    private static final int PERMITS_FOR_OWNING_ACCESS = 2;
    /**
     * @see #computeBlockingOnEdtUnderInheritedAccess
     */
    private static final int PERMITS_FOR_INHERITED_ACCESS = 1;
    /**
     * Don't use acquire / release operations directly,
     *   use {@link #acquireSemaphorePermitsUninterruptibly(int, long)} / {@link #releaseSemaphorePermits} instead.
     */
    private final Semaphore semaphore = new Semaphore(PERMITS_FOR_OWNING_ACCESS, true);


    private static class AsyncTaskResultContainer {
        public boolean taskIsFinished = false;
        public Object result = null;
        public Throwable error = null;
    }

    private class ThreadAcquiredAccessInfo {
        public enum AccessType {
            OWNING,
            INHERITED
        }

        public AccessType accessType = null;
        public int accessReentrancyLevel = 0;
        public final ExclusiveAccessContext accessCtxCached = new ExclusiveAccessContext();
        public final AsyncTaskResultContainer asyncTaskResultContainerCached = new AsyncTaskResultContainer();
    }
    private final ThreadLocal<ThreadAcquiredAccessInfo> threadAcquiredAccessInfo = ThreadLocal.withInitial(ThreadAcquiredAccessInfo::new);


    /**
     * Acquires {@code permits} number of permits from {@link #semaphore},
     *   ignoring thread interruptions.
     * <p>
     * If the current thread is interrupted while waiting for permits then it will continue to wait.
     * When the thread does return from this method its interrupted status will be set.
     *
     * @param permits number of permits to acquire; must be > 0
     * @param timeoutMs values < 0 mean to wait for {@code permits} with no timeout
     * @return {@code false} if and only if passed {@code timeoutMs} is >= 0 and the thread failed to acquire
     *         the requisted number of permits within the provided timeout.
     *         This is the only case when the function can return normally but not having acquired the permits.
     */
    private boolean acquireSemaphorePermitsUninterruptibly(int permits, long timeoutMs) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits=" + permits + " < 1");
        }

        if (timeoutMs < 0) {
            semaphore.acquireUninterruptibly(permits);
            return true;
        }

        boolean result = false;
        boolean interrupted = false;

        long nowMs = System.currentTimeMillis();
        do {
            try {
                if (semaphore.tryAcquire(permits, timeoutMs, TimeUnit.MILLISECONDS)) {
                    result = true;
                    break;
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }

            final long newNowMs = System.currentTimeMillis();
            timeoutMs -= newNowMs - nowMs;
            nowMs = newNowMs;
        } while (timeoutMs >= 0);

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return result;
    }

    private void acquireSemaphorePermitsUninterruptibly(int permits) {
        boolean result = acquireSemaphorePermitsUninterruptibly(permits, -1);
        if (!result) {
            throw new RuntimeException("acquireSemaphorePermitsUninterruptibly with no timeout returned false");
        }
    }

    private void releaseSemaphorePermits(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits=" + permits + " < 1");
        }

        semaphore.release(permits);
    }


    private void acquireReentrantOrOwningAccess(final long timeoutMs) throws Exception {
        final var accessInfo = threadAcquiredAccessInfo.get();

        if (accessInfo.accessType == null) {
            // No access acquired yet

            assert accessInfo.accessReentrancyLevel == 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

            if (!acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_OWNING_ACCESS, timeoutMs)) {
                throw new ExclusiveAccessException("acquireSemaphorePermitsUninterruptibly failed within given " + timeoutMs + " ms");
            }

            accessInfo.accessType = ThreadAcquiredAccessInfo.AccessType.OWNING;
            accessInfo.accessReentrancyLevel = 0;
        }

        assert accessInfo.accessType == ThreadAcquiredAccessInfo.AccessType.OWNING || accessInfo.accessType == ThreadAcquiredAccessInfo.AccessType.INHERITED
               : "Unknown accessType=" + accessInfo.accessType;

        accessInfo.accessReentrancyLevel = Math.max(accessInfo.accessReentrancyLevel + 1, 1);
    }

    private void releaseAccess() {
        final var accessInfo = threadAcquiredAccessInfo.get();

        if (accessInfo.accessType == null) {
            throw new IllegalStateException("Trying to release an access when not having any (accessType == null)");
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (accessInfo.accessReentrancyLevel < 2) {
            final int permitsToRelease = switch (accessInfo.accessType) {
                case OWNING -> PERMITS_FOR_OWNING_ACCESS;
                case INHERITED -> PERMITS_FOR_INHERITED_ACCESS;
                default -> throw new IllegalStateException("Unknown accessType=" + accessInfo.accessType);
            };

            releaseSemaphorePermits(permitsToRelease);

            accessInfo.accessReentrancyLevel = 0;
            accessInfo.accessType = null;
        } else {
            --accessInfo.accessReentrancyLevel;
        }
    }


    /**
     * Must always be paired with {@link #recallEmittedInheritedAccessPermitFromThisThread()}.
     */
    private void emitInheritedAccessPermitFromThisThread() {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException("Trying to share an access when not having any (accessType == null)");
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        releaseSemaphorePermits(PERMITS_FOR_INHERITED_ACCESS);
    }

    private void recallEmittedInheritedAccessPermitFromThisThread() {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException("Trying to take back an access when not having any (accessType == null)");
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        boolean success;
        int fails = 0;
        do {
            try {
                acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_INHERITED_ACCESS);
                success = true;
            } catch (Throwable err) {
                success = false;

                fails = Math.min(fails + 1, 6);
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    if (fails < 5) {
                        log.warning("recallEmittedInheritedAccessPermitFromThisThread: failed acquireSemaphorePermitsUninterruptibly call.", err);
                    } else if (fails == 5) {
                        log.warning("recallEmittedInheritedAccessPermitFromThisThread: 5 failed acquireSemaphorePermitsUninterruptibly calls in a row. Subsequent fails will not be logged.", err);
                    }
                }
            }
        } while (!success);
    }

    /**
     * Must always be paired with {@link #releaseAccess()}.
     */
    private void catchInheritedAccessPermitInThisThread(final long timeoutMs) throws Exception {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType != null) {
            throw new IllegalStateException("This thread already has access=" + accessInfo.accessType);
        }
        assert accessInfo.accessReentrancyLevel == 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (!acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_INHERITED_ACCESS, timeoutMs)) {
            throw new ExclusiveAccessException("acquireSemaphorePermitsUninterruptibly failed within given " + timeoutMs + " ms");
        }

        accessInfo.accessType = ThreadAcquiredAccessInfo.AccessType.INHERITED;
        accessInfo.accessReentrancyLevel = 1;
    }


    private <R> R computeBlockingOnEdtUnderInheritedAccess(SynchronousStep<R> step) throws Exception {
        Objects.requireNonNull(step, "step");

        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException("Trying to share an access for EDT when not owning any (accessType == null)");
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (EventQueue.isDispatchThread()) {
            return step.execute();
        }

        final var asyncTaskResultContainer =
            Objects.requireNonNull(accessInfo.asyncTaskResultContainerCached, "asyncTaskResultContainerCached");

        // So, what's going on here:
        // By this point we're positive that:
        //   1. The current thread is not EDT
        //   2. The current thread has either OWNING or INHERITED access
        //
        // We want kind of "atomically" pass the access to EDT for the duration of the task we're about to post to there.
        // It's done via releasing 1 permit to the semaphore, but only 1. This way we guarantee no other thread can
        //   acquire the access via withExclusiveAccess, because 2 permits are required for that.
        // Then, the EDT "catches" that 1 permit, executes the task and writes the result to asyncTaskResultContainer.
        //
        // asyncTaskResultContainer acts as both a condition variable, which the current thread blocks on until the completion
        //   of the EDT task, and a lightweight future-promise to pass the task's result, which can be either R
        //   or an exception.

        final Runnable wrappedStep = () -> {
            synchronized (asyncTaskResultContainer) {
                try {
                    catchInheritedAccessPermitInThisThread(5000);
                    try {
                        asyncTaskResultContainer.result = step.execute();
                    } finally {
                        releaseAccess();
                    }
                } catch (Throwable err) {
                    asyncTaskResultContainer.error = err;
                } finally {
                    asyncTaskResultContainer.taskIsFinished = true;
                    asyncTaskResultContainer.notify();
                }
            }
        };

        final Object taskResultLocal;
        final Throwable taskErrorLocal;
        synchronized (asyncTaskResultContainer) {
            asyncTaskResultContainer.taskIsFinished = false;
            asyncTaskResultContainer.result = null;
            asyncTaskResultContainer.error = null;

            emitInheritedAccessPermitFromThisThread();
            try {
                EventQueue.invokeLater(wrappedStep);

                // From now on, we MUST wait for the wrappedStep to finish and CANNOT proceed further in any other case
                //   because it would immediately mean that both this thread and EDT have concurrent access to
                //   the InputMethod instance.

                int fails = 0;
                do {
                    try {
                        // Waking up every second just in case to be able to observe taskIsFinished
                        asyncTaskResultContainer.wait(1000);
                    } catch (Throwable err) {
                        // Avoiding breaking the loop at all costs.

                        fails = Math.min(fails + 1, 6);
                        if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                            if (fails < 5) {
                                log.warning("computeBlockingOnEdtUnderInheritedAccess: failed Object.wait call.", err);
                            } else if (fails == 5) {
                                log.warning("computeBlockingOnEdtUnderInheritedAccess: 5 failed Object.wait calls in a row. Subsequent fails will not be logged.", err);
                            }
                        }
                    }
                } while (!asyncTaskResultContainer.taskIsFinished);

                taskResultLocal = asyncTaskResultContainer.result;
                taskErrorLocal = asyncTaskResultContainer.error;
            } finally {
                recallEmittedInheritedAccessPermitFromThisThread();

                asyncTaskResultContainer.taskIsFinished = false;
                asyncTaskResultContainer.result = null;
                asyncTaskResultContainer.error = null;
            }
        }

        if (taskErrorLocal != null) {
            final var toBeThrown = new ExecutionException(taskErrorLocal);
            toBeThrown.addSuppressed(new Throwable("Invoked from here"));
            throw toBeThrown;
        }

        @SuppressWarnings("unchecked")
        final R result = (R)taskResultLocal;
        return result;
    }
}
