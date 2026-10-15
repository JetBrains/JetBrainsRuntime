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
package sun.java2d.skia;

import sun.awt.util.ThreadGroupUtils;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;

import static sun.java2d.pipe.BufferedOpCodes.DISPOSE_CONFIG;
import static sun.java2d.pipe.BufferedOpCodes.SYNC;

/**
 * Render queue of the experimental Skia backend (JBR-10654). Same model as
 * {@code MTLRenderQueue}: operations are encoded into the shared
 * {@link RenderBuffer} on the EDT and interpreted against Skia on a dedicated
 * flusher thread by {@code flushBuffer}.
 */
public final class SKRenderQueue extends RenderQueue {

    private static SKRenderQueue theInstance;
    private final QueueFlusher flusher;

    private SKRenderQueue() {
        flusher = new QueueFlusher();
    }

    public static synchronized SKRenderQueue getInstance() {
        if (theInstance == null) {
            theInstance = new SKRenderQueue();
        }
        return theInstance;
    }

    /** Flushes the queue and waits for the GPU work to complete (Toolkit.sync()). */
    public static void sync() {
        if (theInstance != null) {
            theInstance.lock();
            try {
                RenderBuffer buf = theInstance.getBuffer();
                theInstance.ensureCapacity(4);
                buf.putInt(SYNC);
                theInstance.flushNow();
            } finally {
                theInstance.unlock();
            }
        }
    }

    public static void disposeGraphicsConfig(long pConfigInfo) {
        SKRenderQueue rq = getInstance();
        rq.lock();
        try {
            SKContext.setScratchSurface(pConfigInfo);
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(DISPOSE_CONFIG);
            buf.putLong(pConfigInfo);
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    public static boolean isQueueFlusherThread() {
        return (Thread.currentThread() == getInstance().flusher.thread);
    }

    @Override
    public void flushNow() {
        try {
            flusher.flushNow();
        } catch (Exception e) {
            logger.severe("SKRenderQueue.flushNow: exception occurred: ", e);
        }
    }

    @Override
    public void flushAndInvokeNow(Runnable r) {
        try {
            flusher.flushAndInvokeNow(r);
        } catch (Exception e) {
            logger.severe("SKRenderQueue.flushAndInvokeNow: exception occurred: ", e);
        }
    }

    private native void flushBuffer(long buf, int limit);

    private void flushBuffer() {
        int limit = buf.position();
        if (limit > 0) {
            flushBuffer(buf.getAddress(), limit);
        }
        buf.clear();
        refSet.clear();
    }

    private final class QueueFlusher implements Runnable {
        private boolean needsFlush;
        private Runnable task;
        private Error error;
        private final Thread thread;

        QueueFlusher() {
            thread = new Thread(ThreadGroupUtils.getRootThreadGroup(),
                    this, "Java2D Skia Queue Flusher", 0, false);
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        synchronized void flushNow() {
            needsFlush = true;
            notify();
            while (needsFlush) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    logger.fine("QueueFlusher.flushNow: interrupted");
                }
            }
            if (error != null) {
                throw error;
            }
        }

        synchronized void flushAndInvokeNow(Runnable task) {
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
                        // Wait for flushNow() or the 100 ms timeout, so that the
                        // queue never sits on undisplayed work for long.
                        wait(100);
                        // Flush on the timeout only when the queue can be locked
                        // without blocking and holds something; otherwise keep waiting.
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
                    error = null;
                    flushBuffer();
                    if (task != null) {
                        task.run();
                    }
                } catch (Error e) {
                    logger.severe("QueueFlusher.run: error occurred", e);
                    error = e;
                } catch (Exception x) {
                    logger.severe("QueueFlusher.run: exception occurred", x);
                } finally {
                    if (timedOut) {
                        unlock();
                    }
                    task = null;
                    needsFlush = false;
                    notifyAll();
                }
            }
        }
    }
}
