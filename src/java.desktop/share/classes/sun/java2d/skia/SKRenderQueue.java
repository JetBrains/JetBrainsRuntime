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
 * Render queue of the Skia backend (JBR-10654). Same model as
 * {@code MTLRenderQueue}: operations are encoded into the shared
 * {@link RenderBuffer} under the queue lock (the AWT lock) and interpreted against
 * Skia on a dedicated flusher thread by {@code flushBuffer}: on demand by the thread
 * that needs a result, which waits, or after {@link #FLUSH_TIMEOUT_MILLIS}.
 */
public final class SKRenderQueue extends RenderQueue {

    /**
     * How long the flusher waits for a {@link #flushNow} before it flushes what the queue
     * holds on its own, so that the queue never sits on undisplayed work for long (the
     * Metal and OpenGL queues wait as long).
     */
    private static final long FLUSH_TIMEOUT_MILLIS = 100;

    private static SKRenderQueue theInstance;
    private final QueueFlusher flusher;

    private SKRenderQueue() {
        flusher = new QueueFlusher();
    }

    static synchronized SKRenderQueue getInstance() {
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
                RenderBuffer buffer = theInstance.getBuffer();
                // the opcode alone
                theInstance.ensureCapacity(Integer.BYTES);
                buffer.putInt(SYNC);
                theInstance.flushNow();
            } finally {
                theInstance.unlock();
            }
        }
    }

    /**
     * Disposes the Graphite context of a configuration on the flusher thread
     * ({@code Win32SKGraphicsConfig}'s disposer record).
     *
     * @param nativeConfigInfo {@link SKGraphicsConfig#getNativeConfigInfo}
     */
    static void disposeGraphicsConfig(long nativeConfigInfo) {
        SKRenderQueue renderQueue = getInstance();
        renderQueue.lock();
        try {
            SKContext.setScratchSurface(nativeConfigInfo);
            RenderBuffer buffer = renderQueue.getBuffer();
            // the opcode, then the native configuration
            renderQueue.ensureCapacityAndAlignment(Integer.BYTES + Long.BYTES, Integer.BYTES);
            buffer.putInt(DISPOSE_CONFIG);
            buffer.putLong(nativeConfigInfo);
            renderQueue.flushNow();
        } finally {
            renderQueue.unlock();
        }
    }

    @Override
    public void flushNow() {
        try {
            flusher.flushNow();
        } catch (Exception exception) {
            logger.severe("SKRenderQueue.flushNow: exception occurred: ", exception);
        }
    }

    @Override
    public void flushAndInvokeNow(Runnable task) {
        try {
            flusher.flushAndInvokeNow(task);
        } catch (Exception exception) {
            logger.severe("SKRenderQueue.flushAndInvokeNow: exception occurred: ", exception);
        }
    }

    /**
     * Runs the entries, then submits and presents what they drew. A flush that drew
     * into no window holds its recordings for the next one and returns true;
     * presentAll (the timeout flush) ends the held recordings.
     */
    private native boolean flushBuffer(long buffer, int limit, boolean presentAll);

    /** Recordings held by a flush that presented nothing; the flusher's timeout flush submits them. */
    private volatile boolean pendingPresent;

    /**
     * A direct entry (an operation larger than the buffer, run by flushAndInvokeNow on the
     * flusher after the buffer) ended its flush: whether that holds recordings, as
     * {@link #flushBuffer(long, int, boolean)} returns for the buffer.
     */
    void directEntryDone(boolean held) {
        pendingPresent = held;
    }

    private void flushBuffer(boolean timedOut) {
        int limit = buf.position();
        if (limit > 0 || (timedOut && pendingPresent)) {
            pendingPresent = flushBuffer(buf.getAddress(), limit, timedOut);
        }
        buf.clear();
        refSet.clear();
    }

    private final class QueueFlusher implements Runnable {
        private boolean needsFlush;
        private Runnable task;
        private Error error;

        QueueFlusher() {
            Thread thread = new Thread(ThreadGroupUtils.getRootThreadGroup(),
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
                } catch (InterruptedException exception) {
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
        @SuppressWarnings("InfiniteLoopStatement")   // the flusher is a daemon that lives as long as the process
        public synchronized void run() {
            boolean timedOut = false;
            while (true) {
                while (!needsFlush) {
                    try {
                        timedOut = false;
                        wait(FLUSH_TIMEOUT_MILLIS);
                        // Flush on the timeout only when the queue can be locked
                        // without blocking and holds something; otherwise keep waiting.
                        if (!needsFlush && (timedOut = tryLock())) {
                            if (buf.position() > 0 || pendingPresent) {
                                needsFlush = true;
                            } else {
                                unlock();
                            }
                        }
                    } catch (InterruptedException exception) {
                        logger.fine("QueueFlusher.run: interrupted");
                    }
                }
                try {
                    error = null;
                    flushBuffer(timedOut);
                    if (task != null) {
                        task.run();
                    }
                } catch (Error flushError) {
                    logger.severe("QueueFlusher.run: error occurred", flushError);
                    error = flushError;
                } catch (Exception exception) {
                    logger.severe("QueueFlusher.run: exception occurred", exception);
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
