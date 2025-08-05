/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

package sun.java2d.vulkan;

import jdk.internal.misc.InnocuousThread;
import sun.java2d.pipe.RenderQueue;

import java.security.AccessController;
import java.security.PrivilegedAction;

import static sun.java2d.pipe.BufferedOpCodes.SYNC;

/**
 * VK-specific implementation of RenderQueue.  This class provides a
 * single (daemon) thread that is responsible for periodically flushing
 * the queue.
 */
public class VKRenderQueue extends RenderQueue {

    private static VKRenderQueue theInstance;
    private final QueueFlusher flusher;

    @SuppressWarnings("removal")
    private VKRenderQueue() {
        /*
         * The thread must be a member of a thread group
         * which will not get GCed before VM exit.
         */
        flusher = AccessController.doPrivileged((PrivilegedAction<QueueFlusher>) QueueFlusher::new);
    }

    /**
     * Returns the single VKRenderQueue instance.  If it has not yet been
     * initialized, this method will first construct the single instance
     * before returning it.
     */
    public static synchronized VKRenderQueue getInstance() {
        if (theInstance == null) {
            theInstance = new VKRenderQueue();
        }
        return theInstance;
    }

    /**
     * Flushes the single VKRenderQueue instance synchronously.  If an
     * VKRenderQueue has not yet been instantiated, this method is a no-op.
     * This method is useful in the case of Toolkit.sync(), in which we want
     * to flush the Vulkan pipeline, but only if the Vulkan pipeline is currently
     * enabled.
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

    @Override
    public void flushNow() {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushNow();
        } catch (Exception e) {
            System.err.println("exception in flushNow:");
            e.printStackTrace();
        }
    }

    public void flushAndInvokeNow(Runnable r) {
        // assert lock.isHeldByCurrentThread();
        try {
            flusher.flushAndInvokeNow(r);
        } catch (Exception e) {
            System.err.println("exception in flushAndInvokeNow:");
            e.printStackTrace();
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

    private class QueueFlusher implements Runnable {
        private boolean needsFlush;
        private Runnable task;
        private Error error;
        private final Thread thread;

        public QueueFlusher() {
            thread = InnocuousThread.newThread("Java2D Queue Flusher", this);
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
                } catch (Error e) {
                    error = e;
                } catch (Exception x) {
                    System.err.println("exception in QueueFlusher:");
                    x.printStackTrace();
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
