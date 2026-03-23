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

import sun.awt.wl.WLToolkit;
import sun.util.logging.PlatformLogger;

import java.awt.EventQueue;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


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
            InputMethodThreading.this.computeBlockingOnAWTThreadUnderCurrentOrChildAccess(step, AWTThreadDescriptor.EDT);
        }

        public <R> R computeBlockingOnEdt(SynchronousStep<R> step) throws Exception {
            return InputMethodThreading.this.computeBlockingOnAWTThreadUnderCurrentOrChildAccess(step, AWTThreadDescriptor.EDT);
        }


        public void performBlockingOnWLThread(VoidSynchronousStep step) throws Exception {
            InputMethodThreading.this.computeBlockingOnAWTThreadUnderCurrentOrChildAccess(step, AWTThreadDescriptor.WLThread);
        }

        public <R> R computeBlockingOnWLThread(SynchronousStep<R> step) throws Exception {
            return InputMethodThreading.this.computeBlockingOnAWTThreadUnderCurrentOrChildAccess(step, AWTThreadDescriptor.WLThread);
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
            acquireReentrantOrRootAccess(timeoutMilliseconds);
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
                String errMsg = err.getMessage();
                if (errMsg == null) {
                    errMsg = "caused by " + err.getClass().getName();
                }

                throw new ExclusiveAccessException(supplementExceptionMessage(errMsg), err);
            }
        }
    }

    public Thread getCurrentExclusiveAccessOwner() {
        return currentExclusiveAccessOwner.get();
    }


    public static void invokeOnEdtNowOrLater(final Runnable runnable) {
        invokeOnAWTThreadNowOrLater(runnable, AWTThreadDescriptor.EDT);
    }

    public static void invokeOnWLThreadNowOrLater(final Runnable runnable) {
        invokeOnAWTThreadNowOrLater(runnable, AWTThreadDescriptor.WLThread);
    }

    private static void invokeOnAWTThreadNowOrLater(final Runnable runnable, final AWTThreadDescriptor awtThreadDescriptor) {
        Objects.requireNonNull(runnable, "runnable");
        Objects.requireNonNull(awtThreadDescriptor, "awtThreadDescriptor");

        final boolean isFromTargetThread = awtThreadDescriptor.isCurrentThread();

        final Throwable stacktrace = isFromTargetThread ? null : new Throwable("Invoked from here");
        final Runnable wrapped = () -> {
            try {
                runnable.run();
            } catch (Throwable unexpected) {
                if (stacktrace != null) {
                    unexpected.addSuppressed(stacktrace);
                }

                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning(
                        String.format("invokeOnAWTThreadNowOrLater: unexpected exception on %s from runnable: %s.",
                                      awtThreadDescriptor, runnable),
                        unexpected
                    );
                }

                throw unexpected;
            }
        };

        if (isFromTargetThread) {
            wrapped.run();
        } else {
            awtThreadDescriptor.invokeLater(wrapped);
        }
    }


    /**
     * @see #withExclusiveAccess
     */
    private static final int PERMITS_FOR_ROOT_ACCESS = 2;
    /**
     * @see #computeBlockingOnAWTThreadUnderCurrentOrChildAccess
     */
    private static final int PERMITS_FOR_CHILD_ACCESS = 1;
    /**
     * Don't use acquire / release operations directly,
     *   use {@link #acquireSemaphorePermitsUninterruptibly(int, long)} / {@link #releaseSemaphorePermits} instead.
     */
    private final Semaphore semaphore = new Semaphore(PERMITS_FOR_ROOT_ACCESS, true);
    private final AtomicReference<Thread> currentExclusiveAccessOwner = new AtomicReference<>(null);


    private static class AsyncTaskResultContainer {
        public boolean taskIsFinished = false;
        public Object result = null;
        public Throwable error = null;
    }

    private class ThreadAcquiredAccessInfo {
        public enum ExclusiveAccessType {
            ROOT,
            CHILD
        }

        public ExclusiveAccessType accessType = null;
        public int accessReentrancyLevel = 0;
        /** Not null when and only when accessType == CHILD, points to the thread that has given the access to this thread */
        public Thread childAccessParent = null;
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


    private void acquireReentrantOrRootAccess(final long timeoutMs) throws Exception {
        final var accessInfo = threadAcquiredAccessInfo.get();

        if (accessInfo.accessType == null) {
            // No access acquired yet

            assert accessInfo.accessReentrancyLevel == 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

            if (!acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_ROOT_ACCESS, timeoutMs)) {
                throw new ExclusiveAccessException(supplementExceptionMessage(
                    "acquireSemaphorePermitsUninterruptibly failed within given " + timeoutMs + " ms"
                ));
            }

            accessInfo.accessType = ThreadAcquiredAccessInfo.ExclusiveAccessType.ROOT;
            accessInfo.accessReentrancyLevel = 0;
            accessInfo.childAccessParent = null;

            currentExclusiveAccessOwner.set(Thread.currentThread());
        }

        assert accessInfo.accessType == ThreadAcquiredAccessInfo.ExclusiveAccessType.ROOT || accessInfo.accessType == ThreadAcquiredAccessInfo.ExclusiveAccessType.CHILD
               : "Unknown accessType=" + accessInfo.accessType;

        accessInfo.accessReentrancyLevel = Math.max(accessInfo.accessReentrancyLevel + 1, 1);
    }

    private void releaseAccess() {
        final var accessInfo = threadAcquiredAccessInfo.get();

        if (accessInfo.accessType == null) {
            throw new IllegalStateException(supplementExceptionMessage(
                "Trying to release an access when not having any (accessType == null)"
            ));
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (accessInfo.accessReentrancyLevel < 2) {
            final int permitsToRelease;
            final Thread newExclusiveAccessOwner;
            if (accessInfo.accessType == ThreadAcquiredAccessInfo.ExclusiveAccessType.ROOT) {
                permitsToRelease = PERMITS_FOR_ROOT_ACCESS;
                newExclusiveAccessOwner = null;
            } else if (accessInfo.accessType == ThreadAcquiredAccessInfo.ExclusiveAccessType.CHILD) {
                permitsToRelease = PERMITS_FOR_CHILD_ACCESS;
                newExclusiveAccessOwner = accessInfo.childAccessParent;
            } else {
                throw new IllegalStateException(supplementExceptionMessage(
                    "Unknown accessType=" + accessInfo.accessType
                ));
            }

            releaseSemaphorePermits(permitsToRelease);

            accessInfo.childAccessParent = null;
            accessInfo.accessReentrancyLevel = 0;
            accessInfo.accessType = null;

            currentExclusiveAccessOwner.compareAndSet(Thread.currentThread(), newExclusiveAccessOwner);
        } else {
            --accessInfo.accessReentrancyLevel;
        }
    }


    /**
     * Must always be paired with {@link #recallEmittedChildAccessPermitFromThisThread()}.
     */
    private void emitChildAccessPermitFromThisThread() {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException(supplementExceptionMessage(
                "Trying to share an access when not having any (accessType == null)"
            ));
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        releaseSemaphorePermits(PERMITS_FOR_CHILD_ACCESS);
    }

    private void recallEmittedChildAccessPermitFromThisThread() {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException(supplementExceptionMessage(
                "Trying to take back an access when not having any (accessType == null)"
            ));
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        boolean success;
        int fails = 0;
        do {
            try {
                acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_CHILD_ACCESS);
                success = true;
            } catch (Throwable err) {
                success = false;

                fails = Math.min(fails + 1, 6);
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    if (fails < 5) {
                        log.warning("recallEmittedChildAccessPermitFromThisThread(): failed acquireSemaphorePermitsUninterruptibly call.", err);
                    } else if (fails == 5) {
                        log.warning("recallEmittedChildAccessPermitFromThisThread(): 5 failed acquireSemaphorePermitsUninterruptibly calls in a row. Subsequent fails will not be logged.", err);
                    }
                }
            }
        } while (!success);
    }

    /**
     * Must always be paired with {@link #releaseAccess()}.
     */
    private void catchChildAccessPermitInThisThread(final long timeoutMs) throws Exception {
        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType != null) {
            throw new IllegalStateException(supplementExceptionMessage(
                "This thread already has access=" + accessInfo.accessType
            ));
        }
        assert accessInfo.accessReentrancyLevel == 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (!acquireSemaphorePermitsUninterruptibly(PERMITS_FOR_CHILD_ACCESS, timeoutMs)) {
            throw new ExclusiveAccessException(supplementExceptionMessage(
                "acquireSemaphorePermitsUninterruptibly failed within given " + timeoutMs + " ms"
            ));
        }

        accessInfo.accessType = ThreadAcquiredAccessInfo.ExclusiveAccessType.CHILD;
        accessInfo.accessReentrancyLevel = 1;
        accessInfo.childAccessParent = currentExclusiveAccessOwner.getAndSet(Thread.currentThread());
    }


    private enum AWTThreadDescriptor {
        EDT {
            @Override
            boolean isCurrentThread() {
                return EventQueue.isDispatchThread();
            }

            @Override
            void invokeLater(Runnable runnable) {
                EventQueue.invokeLater(runnable);
            }
        },
        WLThread {
            @Override
            boolean isCurrentThread() {
                return WLToolkit.isWLThread();
            }

            @Override
            void invokeLater(Runnable runnable) {
                WLToolkit.performOnWLThread(runnable);
            }
        };

        abstract boolean isCurrentThread();
        abstract void invokeLater(Runnable runnable);

        @Override
        public String toString() {
            return getClass().getSimpleName() + ":" + name();
        }
    }

    private <R> R computeBlockingOnAWTThreadUnderCurrentOrChildAccess(SynchronousStep<R> step, AWTThreadDescriptor awtThreadDescriptor) throws Exception {
        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest(
                String.format(
                    "computeBlockingOnAWTThreadUnderCurrentOrChildAccess(awtThreadDescriptor=%s).",
                    awtThreadDescriptor
                ),
                new Throwable("Stacktrace")
            );
        }

        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(awtThreadDescriptor, "awtThreadDescriptor");

        final var accessInfo = threadAcquiredAccessInfo.get();
        if (accessInfo.accessType == null) {
            throw new IllegalStateException(supplementExceptionMessage(
                "Trying to share an access for " + awtThreadDescriptor + " when not owning any (accessType == null)"
            ));
        }
        assert accessInfo.accessReentrancyLevel > 0 : "Unexpected accessReentrancyLevel=" + accessInfo.accessReentrancyLevel;

        if (awtThreadDescriptor.isCurrentThread()) {
            return step.execute();
        }

        final var asyncTaskResultContainer =
            Objects.requireNonNull(accessInfo.asyncTaskResultContainerCached, "asyncTaskResultContainerCached");

        // So, what's going on here:
        // By this point we're positive that:
        //   1. The current thread is not the one we need to perform the task on
        //   2. The current thread has either ROOT or CHILD access
        //
        // We want kind of "atomically" pass the access to that thread for the duration of the task we're about to post to there.
        // It's done via releasing 1 semaphore's permit, but only 1. This way we guarantee no other thread can
        //   acquire the access via withExclusiveAccess, because that requires 2 permits.
        // Then, the other thread "catches" that 1 permit, executes the task and writes the result to asyncTaskResultContainer.
        //
        // asyncTaskResultContainer acts as both a condition variable, which the current thread blocks on until the completion
        //   of the task, and a lightweight future-promise to pass the task's result, which can be either R
        //   or an exception.

        final Runnable wrappedStep = () -> {
            synchronized (asyncTaskResultContainer) {
                try {
                    catchChildAccessPermitInThisThread(5000);
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

            emitChildAccessPermitFromThisThread();
            try {
                awtThreadDescriptor.invokeLater(wrappedStep);

                // From now on, we MUST wait for the wrappedStep to finish and CANNOT proceed further in any other case
                //   because it would immediately mean that both the current thread and awtThreadDescriptor's one
                //   have concurrent access to the InputMethod instance.

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
                                log.warning("computeBlockingOnAWTThreadUnderCurrentOrChildAccess: failed Object.wait call.", err);
                            } else if (fails == 5) {
                                log.warning("computeBlockingOnAWTThreadUnderCurrentOrChildAccess: 5 failed Object.wait calls in a row. Subsequent fails will not be logged.", err);
                            }
                        }
                    }
                } while (!asyncTaskResultContainer.taskIsFinished);

                taskResultLocal = asyncTaskResultContainer.result;
                taskErrorLocal = asyncTaskResultContainer.error;
            } finally {
                recallEmittedChildAccessPermitFromThisThread();

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

    private String supplementExceptionMessage(String initialMessage) {
        if (initialMessage == null) return null;
        return String.format(
            "%s. semaphore.availablePermits=[%d] ; current thread=[%s] ; the parent=[%s] ; the exclusive access owner=[%s]",
            initialMessage,
            semaphore.availablePermits(),
            Thread.currentThread(),
            threadAcquiredAccessInfo.get().childAccessParent,
            getCurrentExclusiveAccessOwner()
        );
    }
}
