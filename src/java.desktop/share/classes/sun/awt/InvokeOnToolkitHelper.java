package sun.awt;

import java.awt.*;
import java.awt.event.InvocationEvent;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.*;

/**
 * Used to perform a blocking invocation on Toolkit thread from EDT, preventing a deadlock.
 * The deadlock can happen when EDT and Toolkit perform blocking invocations at the same time.
 * The following is performed to resolve it:
 * 1) The invoker spins a nested event loop on EDT while waiting for the invocation to complete.
 * 2) A separate pool thread is used to perform the invocation.
 */
public class InvokeOnToolkitHelper {
    private ExecutorService executor;
    // every invokeAndWait() pushes a queue of invocations
    private final Stack<LinkedBlockingQueue<InvocationEvent>> invocations = new Stack<>();

    // invocations should be dispatched on proper EDT (per AppContext)
    private static final Map<Thread, InvokeOnToolkitHelper> edt2invokerMap = new ConcurrentHashMap<>();

    private static final int WAIT_LIMIT_SECONDS = 5;

    private InvokeOnToolkitHelper() {}

    /**
     * Invokes the callable on Toolkit thread and blocks until the completion. The method is re-entrant.
     *
     * On macOS it is assumed the callable wraps a native selector. The selector should be executed via [JNFRunLoop performOnMainThreadWaiting:YES ...]
     * so that the doAWTRunLoop on AppKit (which is run in [JNFRunLoop javaRunLoopMode]) accepts it. The callable wrapper should not call any Java code
     * which would normally be called on EDT.
     * <p>
     * If Toolkit posts invocation events caused by the callable, those events are intercepted and dispatched on EDT out of order.
     * <p>
     * When called on non-EDT, the method invokes the callable in place.
     */
    public static <T> T invokeAndBlock(Callable<T> callable) {
        if (callable == null) return null;

        if (EventQueue.isDispatchThread()) {
            InvokeOnToolkitHelper invoker = getInstance(Thread.currentThread());
            if (invoker != null) {
                return invoker.invoke(callable);
            }
        }
        // fallback to default
        try {
            return callable.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    @SuppressWarnings("removal")
    private <T> T invoke(Callable<T> callable) {
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
                                    t.setName("AWT-InvokeOnToolkitHelper " + t.getName());
                                    return t;
                                }
                            })
            );
        }
        LinkedBlockingQueue<InvocationEvent> currentQueue;
        synchronized (invocations) {
            invocations.push(currentQueue = new LinkedBlockingQueue<>());
        }

        FutureTask<T> task = new FutureTask<T>(callable) {
            @Override
            protected void done() {
                synchronized (invocations) {
                    // Done with the current queue, wake it up.
                    invocations.pop().add(new InvocationEvent(executor, () -> {}));
                }
            }
        };
        executor.execute(task);

        try {
            while (!task.isDone() || !currentQueue.isEmpty()) {
                InvocationEvent event = currentQueue.poll(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
                if (event == null) {
                    new RuntimeException("Waiting for the invocation event timed out").printStackTrace();
                    break;
                }
                event.dispatch();
            }
            return task.isDone() ? task.get() : null;

        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Warning: the method is used by the implementation and should not be used by a client.
     *
     * Checks if there's an active InvokeOnToolkitHelper corresponding to the invocation's AppContext,
     * adds the invocation to the InvokeOnToolkitHelper's queue and returns true.
     * Otherwise does nothing and returns false.
     */
    public static boolean offer(InvocationEvent invocation) {
        Object source = invocation.getSource();

        InvokeOnToolkitHelper invoker = (source instanceof Component) ?
                getInstance((Component)source) :
                getInstance(Toolkit.getDefaultToolkit().getSystemEventQueue());

        if (invoker == null) return false;

        synchronized (invoker.invocations) {
            if (!invoker.invocations.isEmpty()) {
                invoker.invocations.peek().add(invocation);
                return true;
            }
        }
        return false;
    }

    private static InvokeOnToolkitHelper getInstance(Component comp) {
        if (comp == null) return null;

        AppContext appContext = SunToolkit.targetToAppContext(comp);
        if (appContext == null) return null;

        return getInstance((EventQueue)appContext.get(AppContext.EVENT_QUEUE_KEY));
    }

    private static InvokeOnToolkitHelper getInstance(EventQueue eq) {
        if (eq == null) return null;

        return getInstance(AWTAccessor.getEventQueueAccessor().getDispatchThread(eq));
    }

    private static InvokeOnToolkitHelper getInstance(Thread edt) {
        if (edt == null) return null;

        return edt2invokerMap.computeIfAbsent(edt, key -> new InvokeOnToolkitHelper());
    }
}
