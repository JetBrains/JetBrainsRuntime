/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

package sun.awt;

import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

public final class KineticScroller {
    public static final int DEFAULT_REFRESH_RATE_HZ = 120;
    private static final int MIN_REFRESH_RATE_HZ = 48;
    private static final int MAX_REFRESH_RATE_HZ = 500;
    private static final long MIN_FRAME_GAP_NS = 2_000_000L;
    private static final long MAX_RECENT_SAMPLE_AGE_NS = 50_000_000L;
    public static final int NON_SHIFT_MODIFIERS_MASK =
            InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    @FunctionalInterface
    public interface ScrollStepConsumer {
        boolean onScrollStep(double deltaX, double deltaY, boolean isLastStep);
    }

    private final KineticScrollPhysics physics;

    private ExecutorService executor;
    private volatile Thread scrollerThread;
    private Object currentToken;
    private long lastSampleNanos;
    private long lastTickNanos;
    private ScrollStepConsumer currentConsumer;

    public KineticScroller() {
        this(new KineticScrollPhysics());
    }

    public KineticScroller(KineticScrollPhysics physics) {
        this.physics = physics;
    }

    public synchronized void addSample(long timeMs, double dx, double dy) {
        lastSampleNanos = System.nanoTime();
        physics.addSample(timeMs, dx, dy);
    }

    public synchronized void clearSamples() {
        lastSampleNanos = 0L;
        physics.clearSamples();
    }

    /**
     * Returns the fling frame period for the given display refresh rate.
     * Falls back to {@link #DEFAULT_REFRESH_RATE_HZ} if the rate is unknown
     * or implausible.
     */
    public static long framePeriodNsFor(int refreshRateHz) {
        if (refreshRateHz < MIN_REFRESH_RATE_HZ || refreshRateHz > MAX_REFRESH_RATE_HZ) {
            refreshRateHz = DEFAULT_REFRESH_RATE_HZ;
        }
        return 1_000_000_000L / refreshRateHz;
    }

    /**
     * Starts a fling.
     *
     * @param framePeriodNs supplies the fling frame period; it is invoked once per fling
     *                      on the scroller thread, so it may query the display device
     */
    public synchronized boolean startFling(long stopTimeMs, LongSupplier framePeriodNs,
                                           ScrollStepConsumer stepConsumer) {
        final long sampleNanos = lastSampleNanos;
        stop();
        if (!physics.startFling(stopTimeMs)) {
            return false;
        }
        currentConsumer = stepConsumer;
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "KineticScroller");
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                scrollerThread = t;
                return t;
            });
        }
        final Object token = new Object();
        currentToken = token;
        executor.execute(() -> runFlingLoop(token, sampleNanos, framePeriodNs));
        return true;
    }

    public synchronized void stop() {
        currentToken = null;
        physics.stop();
        currentConsumer = null;
        Thread t = scrollerThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private synchronized boolean isActive(Object token) {
        return token == currentToken;
    }

    private void runFlingLoop(Object token, long sampleNanos, LongSupplier framePeriodSupplier) {
        final long framePeriodNs = framePeriodSupplier.getAsLong();
        long deadlineNs;
        synchronized (this) {
            if (token != currentToken) {
                return;
            }
            final long now = System.nanoTime();
            final boolean hasRecentSample = sampleNanos != 0L && (now - sampleNanos) >= 0L
                    && (now - sampleNanos) <= MAX_RECENT_SAMPLE_AGE_NS;
            deadlineNs = hasRecentSample
                    ? Math.max(now, sampleNanos + framePeriodNs)
                    : now + framePeriodNs;
            lastTickNanos = deadlineNs - framePeriodNs;
        }
        while (isActive(token)) {
            while (isActive(token)) {
                long waitNs = deadlineNs - System.nanoTime();
                if (waitNs < 1_000_000L) {
                    break;
                }
                LockSupport.parkNanos(waitNs);
            }
            if (!isActive(token)) {
                return;
            }
            try {
                EventQueue.invokeAndWait(() -> tick(token));
            } catch (InterruptedException | InvocationTargetException ignored) {
                return;
            }
            deadlineNs += framePeriodNs;
            long afterTickNs = System.nanoTime();
            if (deadlineNs < afterTickNs + MIN_FRAME_GAP_NS) {
                deadlineNs = afterTickNs + MIN_FRAME_GAP_NS;
            }
        }
    }

    private void tick(Object token) {
        final ScrollStepConsumer consumer;
        final double dx;
        final double dy;
        final boolean continues;
        synchronized (this) {
            if (token != currentToken) {
                return;
            }
            final long now = System.nanoTime();
            final double elapsedMs = Math.max(1.0, Math.min(24.0, (now - lastTickNanos) / 1_000_000.0));
            lastTickNanos = now;
            continues = physics.advance(elapsedMs);
            dx = physics.getDeltaX();
            dy = physics.getDeltaY();
            consumer = currentConsumer;
            if (!continues) {
                stop();
            }
        }
        try {
            if (!consumer.onScrollStep(dx, dy, !continues)) {
                stop();
            }
        } catch (RuntimeException err) {
            stop();
            throw err;
        }
    }
}
