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

    /**
     * EXPERIMENT (x64hw, 2026-11-04), {@code sun.java2d.skia.asyncPresent=true}: the flush that ends a
     * window's paint cycle ({@link SKSurfaceData#requestPresent}, most of a frame's work, since the
     * buffer rarely fills) is handed to the flusher without waiting for it, so the EDT goes on with its
     * events while the frame is encoded and presented. Every other flush ({@link #flushNow}, also
     * {@code flushAndInvokeNow}, {@code sync}, read-backs, software sources, disposals) still waits,
     * first for a hand-off in flight, so whatever relies on the queue having run still sees it run.
     * The hand-off copies the buffer's content to a second one (callers keep a reference to
     * {@link #getBuffer()} across {@code ensureCapacity}, so the buffer itself cannot change), and
     * the references that keep the queued ops' objects alive go along with it. The flusher runs it
     * without the AWT lock held by anyone; the native backend serializes its entry points instead
     * (SKBackend_EntryMutex).
     */
    static final boolean ASYNC_PRESENT = Boolean.getBoolean("sun.java2d.skia.asyncPresent");

    /** Ends a paint cycle's flush: handed to the flusher ({@link #ASYNC_PRESENT}) or run now. Queue lock held. */
    void flushForPresent() {
        if (!ASYNC_PRESENT) {
            flushNow();
            return;
        }
        try {
            flusher.handOff();
        } catch (Exception e) {
            logger.severe("SKRenderQueue.flushForPresent: exception occurred: ", e);
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

    /**
     * Runs the entries; presentAll presents every window drawn into and not yet
     * presented and submits all pending work (the timeout flush). Returns whether
     * such windows or submits remain ({@code sun.java2d.skia.present=cycle},
     * {@code sun.java2d.skia.submit=present}; never under the defaults).
     */
    private native boolean flushBuffer(long buf, int limit, boolean presentAll);

    /** Windows drawn into but not presented yet, or work not submitted; the flusher's timeout flush sends them. */
    private volatile boolean pendingPresent;

    /** Whether windows drawn into wait to be presented (read under the queue lock). */
    boolean hasPendingPresent() {
        return pendingPresent;
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

        // ASYNC_PRESENT: the content handed off and not run yet, the references it needs alive
        private RenderBuffer handed;
        private boolean handedPending;
        private java.util.Set<Object> handedRefs = new java.util.HashSet<>();

        /** Hands the queue's content to the flusher (ASYNC_PRESENT); waits only for an earlier hand-off. */
        synchronized void handOff() {
            // the flusher holds this monitor while it runs a hand-off, so being here means none is
            // running; one handed and not taken yet is waited for (at most one in flight)
            while (handedPending) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    logger.fine("QueueFlusher.handOff: interrupted");
                }
            }
            if (buf.position() == 0 && !pendingPresent) {
                return;
            }
            if (handed == null) {
                handed = RenderBuffer.allocate(buf.capacity());
            }
            buf.copyTo(handed);
            buf.clear();
            java.util.Set<Object> refs = handedRefs;
            handedRefs = refSet;
            refSet = refs;
            handedPending = true;
            notifyAll();
        }

        // Runs the handed-off content: in the order given, before any flush asked for later.
        private void runHandOff() {
            try {
                if (handed.position() > 0 || pendingPresent) {
                    pendingPresent = flushBuffer(handed.getAddress(), handed.position(), false);
                }
            } catch (Error e) {
                logger.severe("QueueFlusher.runHandOff: error occurred", e);
                error = e;   // thrown by the next flushNow
            } catch (Exception x) {
                logger.severe("QueueFlusher.runHandOff: exception occurred", x);
            } finally {
                handed.clear();
                handedRefs.clear();
                handedPending = false;
                notifyAll();
            }
        }

        @Override
        public synchronized void run() {
            boolean timedOut = false;
            while (true) {
                if (handedPending) {
                    runHandOff();
                    continue;
                }
                while (!needsFlush && !handedPending) {
                    try {
                        timedOut = false;
                        // Wait for flushNow() or the 100 ms timeout, so that the
                        // queue never sits on undisplayed work for long.
                        wait(100);
                        // Flush on the timeout only when the queue can be locked
                        // without blocking and holds something; otherwise keep waiting.
                        // A hand-off's notify is not a timeout: it runs first, and a timeout
                        // flush here would present the windows before it (mid-cycle content).
                        if (!needsFlush && !handedPending && (timedOut = tryLock())) {
                            if (buf.position() > 0 || pendingPresent) {
                                needsFlush = true;
                            } else {
                                unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        logger.fine("QueueFlusher.run: interrupted");
                    }
                }
                if (!needsFlush) {
                    continue;   // a hand-off arrived: run it first (the loop's top)
                }
                try {
                    error = null;
                    flushBuffer(timedOut);
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
