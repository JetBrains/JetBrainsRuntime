package sun.awt;

import sun.font.FontUtilities;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.event.InvocationEvent;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Used to perform a cross threads (EventDispatch, Toolkit) execution so that the execution does not cause a deadlock.
 *
 * Note: the log messages are tested by jdk/jb/java/awt/Toolkit/LWCToolkitInvokeAndWaitTest.java
 */
public class AWTThreading {
    private static final PlatformLogger logger = PlatformLogger.getLogger(AWTThreading.class.getName());

    private static final Runnable EMPTY_RUNNABLE = () -> {};

    private static final AtomicReference<Function<Thread, AWTThreading>> theAWTThreadingFactory =
            new AtomicReference<>(AWTThreading::new);

    private final Thread eventDispatchThread;

    private ExecutorService executor;
    // every invokeAndWait() pushes a queue of invocations
    private final Stack<TrackingQueue> invocations = new Stack<>();

    private int level; // re-entrance level

    private final List<CompletableFuture<Void>> eventDispatchThreadStateNotifiers = new ArrayList<>();
    private volatile boolean isEventDispatchThreadFree;

    // invocations should be dispatched on proper EDT (per AppContext)
    private static final Map<Thread, AWTThreading> EDT_TO_INSTANCE_MAP = new ConcurrentHashMap<>();

    @SuppressWarnings("serial")
    private static class TrackingQueue extends LinkedBlockingQueue<InvocationEvent> {}

    /**
     * WARNING: for testing purpose, use {@link AWTThreading#getInstance(Thread)} instead.
     */
    public AWTThreading(Thread edt) {
        eventDispatchThread = edt;
    }

    /**
     * Executes a callable from EventDispatch thread (EDT). It's assumed the callable either performs a blocking execution on Toolkit
     * or waits a notification from Toolkit. The method is re-entrant.
     * <p>
     * Currently only macOS is supported. The callable can wrap a native obj-c selector. The selector should be executed via
     * [JNFRunLoop performOnMainThreadWaiting:YES ...] so that doAWTRunLoop on AppKit (which is run in [JNFRunLoop javaRunLoopMode]) accepts it.
     * The callable should not call any Java code that would normally be called on EDT.
     * <p>
     * A deadlock can happen when the callable triggers any blocking invocation from Toolkit to EDT, or when Toolkit already waits in
     * such blocking invocation. To avoid that:
     * <ul>
     * <li>The callback execution is delegated to a dedicated pool thread.
     * <li>All invocation events, initiated by Toolkit via invokeAndWait(), are tracked via a dedicated queue.
     * <li>All the tracked invocation events are dispatched on EDT out of order (EventQueue) during the callback execution.
     * <li>In case of a re-entrant method call, all the tracked invocation events coming after the call are dispatched first.
     * </ul><p>
     * When called on non-EDT, or on non-macOS, the method executes the callable just in place.
     */
    public static <T> T executeWaitToolkit(Callable<T> callable) {
        return executeWaitToolkit(callable, -1, null);
    }

    /**
     * A boolean value passed to the consumer indicates whether the consumer should perform
     * a synchronous invocation on the Toolkit thread and wait, or return immediately.
     *
     * @see #executeWaitToolkit(Callable).
     */
    public static void executeWaitToolkit(Consumer<Boolean> consumer) {
        boolean wait = EventQueue.isDispatchThread();
        executeWaitToolkit(() -> {
            consumer.accept(wait);
            return null;
        });
    }

    /**
     * @see #executeWaitToolkit(Callable).
     */
    public static void executeWaitToolkit(Runnable runnable) {
        executeWaitToolkit(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Same as {@link #executeWaitToolkit(Callable)} except that the method waits no longer than the specified timeout.
     */
    public static <T> T executeWaitToolkit(Callable<T> callable, long timeout, TimeUnit unit) {
        if (callable == null) return null;

        boolean isEDT = EventQueue.isDispatchThread();

        if (FontUtilities.isMacOSX && isEDT) {
            AWTThreading instance = getInstance(Thread.currentThread());
            if (instance != null) {
                return instance.execute(callable, timeout, unit);
            }
        }

        try {
            return callable.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressWarnings("removal")
    private <T> T execute(Callable<T> callable, long timeout, TimeUnit unit) {
        assert EventQueue.isDispatchThread();
        if (executor == null) {
            // init on EDT
            AccessController.doPrivileged((PrivilegedAction<?>)() ->
                    executor = new ThreadPoolExecutor(1, Integer.MAX_VALUE,
                            60L, TimeUnit.SECONDS,
                            new SynchronousQueue<>(),
                            new ThreadFactory() {
                                private final ThreadFactory factory = Executors.privilegedThreadFactory();
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread t = factory.newThread(r);
                                    t.setDaemon(true);
                                    t.setName(AWTThreading.class.getSimpleName() + " " + t.getName());
                                    return t;
                                }
                            })
            );
        }
        level++;
        try {
            TrackingQueue currentQueue;
            synchronized (invocations) {
                if (level == 1 && invocations.size() == 1) {
                    currentQueue = invocations.peek();
                } else {
                    invocations.push(currentQueue = new TrackingQueue());
                }
            }

            FutureTask<T> task = new FutureTask<>(callable) {
                @Override
                protected void done() {
                    synchronized (invocations) {
                        invocations.remove(currentQueue);
                        // add dummy event to wake up the queue
                        currentQueue.add(new InvocationEvent(new Object(), ()  -> {}));
                    }
                }
            };
            executor.execute(task);

            try {
                while (!task.isDone() || !currentQueue.isEmpty()) {
                    InvocationEvent event;
                    if (timeout >= 0 && unit != null) {
                        event = currentQueue.poll(timeout, unit);
                    } else {
                        event = currentQueue.take();
                    }
                    if (event == null) {
                        task.cancel(false);
                        synchronized (invocations) {
                            invocations.remove(currentQueue);
                        }
                        new RuntimeException("Waiting for the invocation event timed out").printStackTrace();
                        break;
                    }
                    event.dispatch();
                }
                return task.isCancelled() ? null : task.get();

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        } finally {
            level--;
        }
        return null;
    }

    /**
     * Used by implementation.
     * Creates an invocation event and adds it to the tracking queue.
     * It's assumed the event is then posted to EventQueue.
     * The following is provided:
     * <ul>
     * <li>If the event is first dispatched from EventQueue - it gets removed from the tracking queue.
     * <li>If the event is first dispatched from the tracking queue - its dispatching on EventQueue will be noop.
     * <ul>
     *
     * @param source the source of the event
     * @param onDispatched called back on event dispatching
     * @param catchThrowables should catch Throwable's or propagate to the EventDispatch thread's loop
     */
    public static TrackedInvocationEvent createAndTrackInvocationEvent(Object source,
                                                                       Runnable onDispatched,
                                                                       boolean catchThrowables)
    {
        AWTThreading instance = getInstance(source);
        if (instance != null) {
            synchronized (instance.invocations) {
                if (instance.invocations.isEmpty()) {
                    instance.invocations.push(new TrackingQueue());
                }
                final TrackingQueue queue = instance.invocations.peek();
                final AtomicReference<TrackedInvocationEvent> eventRef = new AtomicReference<>();

                eventRef.set(TrackedInvocationEvent.create(
                    source,
                    onDispatched,
                    new Runnable() {
                        final WeakReference<TrackingQueue> queueRef = new WeakReference<>(queue);

                        @Override
                        public void run() {
                            TrackingQueue queue = queueRef.get();
                            queueRef.clear();
                            if (queue != null) {
                                queue.remove(eventRef.get());
                            }
                        }
                    },
                    catchThrowables));

                queue.add(eventRef.get());
                return eventRef.get();
            }
        }
        return TrackedInvocationEvent.create(source, onDispatched, () -> {}, catchThrowables);
    }

    @SuppressWarnings("serial")
    public static class TrackedInvocationEvent extends InvocationEvent {
        private final long creationTime = System.currentTimeMillis();
        private final Throwable throwable = new Throwable();
        private final CompletableFuture<Void> futureResult = new CompletableFuture<>();

        // dispatched or disposed
        private final AtomicBoolean isFinished = new AtomicBoolean(false);

        static TrackedInvocationEvent create(Object source,
                                             Runnable onDispatch,
                                             Runnable onDone,
                                             boolean catchThrowables)
        {
            final AtomicReference<TrackedInvocationEvent> eventRef = new AtomicReference<>();
            eventRef.set(new TrackedInvocationEvent(
                source,
                onDispatch,
                () -> {
                    if (onDone != null) {
                        onDone.run();
                    }
                    TrackedInvocationEvent thisEvent = eventRef.get();
                    if (!thisEvent.isDispatched()) {
                        // If we're here - this {onDone} is being disposed.
                        thisEvent.finishIfNotYet(() ->
                            // If we're here - this {onDone} is called by the outer AWTAccessor.getInvocationEventAccessor().dispose()
                            // which we do not control, so complete here.
                            thisEvent.futureResult.completeExceptionally(new Throwable("InvocationEvent was disposed"))
                        );
                    }
                },
                catchThrowables));
            return eventRef.get();
        }

        protected TrackedInvocationEvent(Object source, Runnable onDispatched, Runnable onDone, boolean catchThrowables) {
            super(source,
                  Optional.of(onDispatched).orElse(EMPTY_RUNNABLE),
                  Optional.of(onDone).orElse(EMPTY_RUNNABLE),
                  catchThrowables);

            futureResult.whenComplete((r, ex) -> {
                if (ex != null) {
                    String message = ex.getMessage() + " (awaiting " + (System.currentTimeMillis() - creationTime) + " ms)";
                    if (logger.isLoggable(PlatformLogger.Level.FINE)) {
                        ex.initCause(throwable);
                        logger.fine(message, ex);
                    } else if (logger.isLoggable(PlatformLogger.Level.INFO)) {
                        StackTraceElement[] stack = throwable.getStackTrace();
                        logger.info(message + ". Originated at " + stack[stack.length - 1]);
                    }
                }
            });
        }

        @Override
        public void dispatch() {
            finishIfNotYet(super::dispatch);
            futureResult.complete(null);
        }

        public void dispose(String reason) {
            finishIfNotYet(() -> AWTAccessor.getInvocationEventAccessor().dispose(this));
            futureResult.completeExceptionally(new Throwable(reason));
        }

        private void finishIfNotYet(Runnable finish) {
            if (!isFinished.getAndSet(true)) {
                finish.run();
            }
        }

        /**
         * Returns whether the event is dispatched or disposed.
         */
        public boolean isDone() {
            return futureResult.isDone();
        }

        /**
         * Calls the runnable when it's done (immediately if it's done).
         */
        public void onDone(Runnable runnable) {
            futureResult.whenComplete((r, ex) -> Optional.of(runnable).orElse(EMPTY_RUNNABLE).run());
        }
    }

    public static AWTThreading getInstance(Object obj) {
        if (obj == null) {
            return getInstance(Toolkit.getDefaultToolkit().getSystemEventQueue());
        }

        AppContext appContext = SunToolkit.targetToAppContext(obj);
        if (appContext == null) {
            return getInstance(Toolkit.getDefaultToolkit().getSystemEventQueue());
        }

        return getInstance((EventQueue)appContext.get(AppContext.EVENT_QUEUE_KEY));
    }

    public static AWTThreading getInstance(EventQueue eq) {
        if (eq == null) return null;

        return getInstance(AWTAccessor.getEventQueueAccessor().getDispatchThread(eq));
    }

    public static AWTThreading getInstance(Thread edt) {
        assert edt != null;

        return EDT_TO_INSTANCE_MAP.computeIfAbsent(edt, key -> theAWTThreadingFactory.get().apply(edt));
    }

    public Thread getEventDispatchThread() {
        return eventDispatchThread;
    }

    /**
     * Sets {@code AWTThreading} instance factory.
     * WARNING: for testing purpose.
     */
    public static void setAWTThreadingFactory(Function<Thread, AWTThreading> factory) {
        theAWTThreadingFactory.set(factory);
    }

    public void notifyEventDispatchThreadFree() {
        List<CompletableFuture<Void>> notifiers = Collections.emptyList();
        synchronized (eventDispatchThreadStateNotifiers) {
            isEventDispatchThreadFree = true;
            if (eventDispatchThreadStateNotifiers.size() > 0) {
                notifiers = List.copyOf(eventDispatchThreadStateNotifiers);
            }
        }
        if (logger.isLoggable(PlatformLogger.Level.FINER)) {
            logger.finer("notifyEventDispatchThreadFree");
        }
        // notify callbacks out of the synchronization block
        notifiers.forEach(f -> f.complete(null));
    }

    public void notifyEventDispatchThreadBusy() {
        synchronized (eventDispatchThreadStateNotifiers) {
            isEventDispatchThreadFree = false;
        }
        if (logger.isLoggable(PlatformLogger.Level.FINER)) {
            logger.finer("notifyEventDispatchThreadBusy");
        }
    }

    /**
     * Sets a callback and returns a {@code CompletableFuture} reporting the case when the associated EventDispatch thread
     * has gone sleeping and stopped dispatching events because of empty EventQueue. If the EventDispatch thread is free
     * at the moment then the callback is called immediately on the caller's thread and the future completes.
     */
    public CompletableFuture<Void> onEventDispatchThreadFree(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.thenRun(Optional.of(runnable).orElse(EMPTY_RUNNABLE));

        if (!isEventDispatchThreadFree) {
            synchronized (eventDispatchThreadStateNotifiers) {
                if (!isEventDispatchThreadFree) {
                    eventDispatchThreadStateNotifiers.add(future);
                    future.whenComplete((r, ex) -> {
                        synchronized (eventDispatchThreadStateNotifiers) {
                            eventDispatchThreadStateNotifiers.remove(future);
                        }
                    });
                    return future;
                }
            }
        }
        if (logger.isLoggable(PlatformLogger.Level.FINER)) {
            logger.finer("onEventDispatchThreadFree: free at the moment");
        }
        future.complete(null);
        return future;
    }
}
