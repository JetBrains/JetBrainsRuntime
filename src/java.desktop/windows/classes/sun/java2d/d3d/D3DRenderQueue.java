/*
 * Copyright (c) 2007, 2026, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.d3d;

import sun.awt.AWTThreading;
import sun.awt.util.ThreadGroupUtils;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;

import static sun.java2d.pipe.BufferedOpCodes.*;
import java.util.concurrent.TimeUnit;

/**
 * D3D-specific implementation of RenderQueue.
 */
public final class D3DRenderQueue extends RenderQueue {

    private static D3DRenderQueue theInstance;
    private final QueueFlusher flusher;

    private D3DRenderQueue() {
        /*
         * The thread must be a member of a thread group
         * which will not get GCed before VM exit.
         */
        flusher = new QueueFlusher();
    }

    /**
     * Returns the single D3DRenderQueue instance.  If it has not yet been
     * initialized, this method will first construct the single instance
     * before returning it.
     */
    public static synchronized D3DRenderQueue getInstance() {
        if (theInstance == null) {
            theInstance = new D3DRenderQueue();
        }
        return theInstance;
    }

    /**
     * Flushes the single D3DRenderQueue instance synchronously.  If an
     * D3DRenderQueue has not yet been instantiated, this method is a no-op.
     * This method is useful in the case of Toolkit.sync(), in which we want
     * to flush the D3D pipeline, but only if the D3D pipeline is currently
     * enabled.  Since this class has few external dependencies, callers need
     * not be concerned that calling this method will trigger initialization
     * of the D3D pipeline and related classes.
     */
    public static void sync() {
        if (theInstance != null) {
            // need to make sure any/all screen surfaces are presented prior
            // to completing the sync operation
            D3DSurfaceData.displayAllBuffersContent();

            theInstance.lock();
            try {
                theInstance.ensureCapacity(4);
                theInstance.getBuffer().putInt(SYNC);
                theInstance.flushNow();
            } finally {
                theInstance.unlock();
            }
        }
    }

    /**
     * Attempt to restore the devices if they're in the lost state.
     * (used when a full-screen window is activated/deactivated)
     */
    public static void restoreDevices() {
        D3DRenderQueue rq = getInstance();
        rq.lock();
        try {
            rq.ensureCapacity(4);
            rq.getBuffer().putInt(RESTORE_DEVICES);
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    /**
     * Disposes the native memory associated with the given native
     * graphics config info pointer on the single queue flushing thread.
     */
    public static void disposeGraphicsConfig(long pConfigInfo) {
        D3DRenderQueue rq = getInstance();
        rq.lock();
        try {

            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(DISPOSE_CONFIG);
            buf.putLong(pConfigInfo);

            // this call is expected to complete synchronously, so flush now
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    /**
     * Returns true if the current thread is the OGL QueueFlusher thread.
     */
    public static boolean isQueueFlusherThread() {
        return (Thread.currentThread() == getInstance().flusher.thread);
    }

    @Override
    public void flushNow() {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushNow();
        } catch (Exception e) {
            logger.severe("D3DRenderQueue.flushNow: exception occurred: ", e);
        }
    }

    @Override
    public void flushAndInvokeNow(Runnable r) {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushAndInvokeNow(r);
        } catch (Exception e) {
            logger.severe("D3DRenderQueue.flushAndInvokeNow: exception occurred: ", e);
        }
    }

    private native void flushBuffer(long buf, int limit);

    private void flushBuffer() {
        // assert lock.isHeldByCurrentThread();
        int limit = buf.position();
        if (limit > 0) {
            // process the queue
            flushBuffer(buf.getAddress(), limit);
        }
        // reset the buffer position
        buf.clear();
        // clear the set of references, since we no longer need them
        refSet.clear();
    }

    public static native int getFramePresentedStatus();
    public static native int setPresentStatistic(int status);

    private final class QueueFlusher implements Runnable {
        private final static long NOTIFY_WAIT_TIMEOUT_MS = 100L;
        private final static long AWT_WAIT_TIMEOUT = 5L;

        private volatile boolean needsFlush;

        private Runnable task;
        private Error error;
        private final Thread thread;

        public QueueFlusher() {
            String name = "Java2D Queue Flusher";
            thread = new Thread(ThreadGroupUtils.getRootThreadGroup(),
                    this, name, 0, false);
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        public void flushNow() {
            flushNow(null);
        }

        private void flushNow(Runnable task) {
            Error err = null;
            synchronized (this) {
                if (task != null) {
                    this.task = task;
                }
                // wake up the flusher
                needsFlush = true;
                notifyAll();

                // wait for flush to complete
                try {
                    wait(NOTIFY_WAIT_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    logger.fine("QueueFlusher.flushNow: interrupted");
                }
                err = error;
            }
            if ((err == null) && needsFlush) {
                // if we still wait for flush then avoid potential deadlock
                err = AWTThreading.executeWaitToolkit(() -> {
                    synchronized (QueueFlusher.this) {
                        while (needsFlush) {
                            try {
                                QueueFlusher.this.wait();
                            } catch (InterruptedException e) {
                                logger.fine("QueueFlusher.wait: interrupted");
                            }
                        }
                        return error;
                    }
                }, AWT_WAIT_TIMEOUT, TimeUnit.SECONDS);
            }
            // re-throw any error that may have occurred during the flush
            if (err != null) {
                throw err;
            }
        }

        public void flushAndInvokeNow(Runnable task) {
            flushNow(task);
        }

        @Override
        public synchronized void run() {
            boolean locked = false;
            while (true) {
                while (!needsFlush) {
                    try {
                        locked = false;
                        /*
                         * Wait until we're woken up with a flushNow() call,
                         * or the timeout period elapses (so that we can
                         * flush the queue periodically).
                         */
                        wait(NOTIFY_WAIT_TIMEOUT_MS);
                        /*
                         * We will automatically flush the queue if the
                         * following conditions apply:
                         *   - the wait() timed out
                         *   - we can lock the queue (without blocking)
                         *   - there is something in the queue to flush
                         * Otherwise, just continue (we'll flush eventually).
                         */
                        if (!needsFlush && (locked = tryLock())) {
                            if (buf.position() > 0) {
                                needsFlush = true;
                            } else {
                                unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        logger.fine("QueueFlusher.run: interrupted");
                    }
                }
                try {
                    // reset the throwable state
                    error = null;
                    // flush the buffer now
                    flushBuffer();
                    // if there's a task, invoke that now as well
                    if (task != null) {
                        task.run();
                    }
                } catch (Error err) {
                    logger.severe("QueueFlusher.run: error occurred: ", err);
                    error = err;
                } catch (Exception e) {
                    logger.severe("QueueFlusher.run: exception occurred: ", e);
                } finally {
                    if (locked) {
                        unlock();
                    }
                    task = null;
                    // allow the waiting thread to continue
                    needsFlush = false;
                    notifyAll();
                }
            }
        }
    }
}
