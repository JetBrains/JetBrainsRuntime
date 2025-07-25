/*
 * Copyright (c) 2007, 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.metal;

import sun.awt.util.ThreadGroupUtils;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;

import static sun.java2d.pipe.BufferedOpCodes.DISPOSE_CONFIG;
import static sun.java2d.pipe.BufferedOpCodes.SYNC;

/**
 * MTL-specific implementation of RenderQueue.  This class provides a
 * single (daemon) thread that is responsible for periodically flushing
 * the queue, thus ensuring that only one thread communicates with the native
 * OpenGL libraries for the entire process.
 */
public final class MTLRenderQueue extends RenderQueue {

    private static MTLRenderQueue theInstance;
    private final QueueFlusher flusher;

    private MTLRenderQueue() {
        /*
         * The thread must be a member of a thread group
         * which will not get GCed before VM exit.
         */
        flusher = new QueueFlusher();
    }

    /**
     * Returns the single MTLRenderQueue instance.  If it has not yet been
     * initialized, this method will first construct the single instance
     * before returning it.
     */
    public static synchronized MTLRenderQueue getInstance() {
        if (theInstance == null) {
            theInstance = new MTLRenderQueue();
        }
        return theInstance;
    }

    /**
     * Flushes the single MTLRenderQueue instance synchronously.  If an
     * MTLRenderQueue has not yet been instantiated, this method is a no-op.
     * This method is useful in the case of Toolkit.sync(), in which we want
     * to flush the MTL pipeline, but only if the MTL pipeline is currently
     * enabled.  Since this class has few external dependencies, callers need
     * not be concerned that calling this method will trigger initialization
     * of the MTL pipeline and related classes.
     */
    public static void sync() {
        if (theInstance != null) {
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
     * Disposes the native memory associated with the given native
     * graphics config info pointer on the single queue flushing thread.
     */
    public static void disposeGraphicsConfig(long pConfigInfo) {
        MTLRenderQueue rq = getInstance();
        rq.lock();
        try {
            // make sure we make the context associated with the given
            // GraphicsConfig current before disposing the native resources
            MTLContext.setScratchSurface(pConfigInfo);

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
     * Returns true if the current thread is the MTL QueueFlusher thread.
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
            logger.severe("MTLRenderQueue.flushNow: exception occurred: ", e);
        }
    }

    @Override
    public void flushAndInvokeNow(Runnable r) {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushAndInvokeNow(r);
        } catch (Exception e) {
            logger.severe("MTLRenderQueue.flushAndInvokeNow: exception occurred: ", e);
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

    private final class QueueFlusher implements Runnable {
        private boolean needsFlush;
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

        public synchronized void flushNow() {
            // wake up the flusher
            needsFlush = true;
            notify();

            // wait for flush to complete
            while (needsFlush) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    logger.fine("QueueFlusher.flushNow: interrupted");
                }
            }

            // re-throw any error that may have occurred during the flush
            if (error != null) {
                throw error;
            }
        }

        public synchronized void flushAndInvokeNow(Runnable task) {
            this.task = task;
            flushNow();
        }

        @Override
        public synchronized void run() {
            boolean timedOut = false;
            while (true) {
                while (!needsFlush) {
                    try {
                        timedOut = false;
                        /*
                         * Wait until we're woken up with a flushNow() call,
                         * or the timeout period elapses (so that we can
                         * flush the queue periodically).
                         */
                        wait(100);
                        /*
                         * We will automatically flush the queue if the
                         * following conditions apply:
                         *   - the wait() timed out
                         *   - we can lock the queue (without blocking)
                         *   - there is something in the queue to flush
                         * Otherwise, just continue (we'll flush eventually).
                         */
                        if (!needsFlush && (timedOut = tryLock())) {
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
                    if (timedOut) {
                        unlock();
                    }
                    task = null;
                    // allow the waiting thread to continue
                    needsFlush = false;
                    notify();
                }
            }
        }
    }
}
