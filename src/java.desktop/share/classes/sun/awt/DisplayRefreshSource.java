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

package sun.awt;

import com.jetbrains.exported.JBRApi;
import jdk.internal.misc.InnocuousThread;
import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The source of the refreshes of one display.
 * <p>
 * It collects one-shot frame requests and runs them on the event dispatch thread at the next refresh.
 * A subclass connects the source to the vsync signal of the platform:
 * {@link #startVSync(VSyncRun)} starts a thread of the signal,
 * and the thread reports each refresh with {@link VSyncRun#onVSync(long, long)}.
 * <p>
 * The vsync signal runs only while requests come. It stops {@link #IDLE_STOP_NANOS} after the last one.
 * A change of the display configuration starts the signal anew, see {@link #restartVSync()}.
 * While the platform gives no signal, the source ticks on a timer with its nominal period,
 * and tries to start the signal again each {@link #START_RETRY_NANOS}.
 * <p>
 * The thread of the vsync signal never takes the lock of the source, and the source never waits for that thread.
 * <p>
 * The JBR API service {@code DisplayRefresh} of each platform passes the frame requests to the source of a window.
 */
public abstract class DisplayRefreshSource implements DisplayChangedListener {
    /** Receives one frame. It is the JBR-side view of {@code com.jetbrains.DisplayRefresh.FrameCallback}. */
    @JBRApi.Provided("DisplayRefresh.FrameCallback")
    public interface Callback {
        void onFrame(long frameTimeNanos, long refreshPeriodNanos);
    }

    /**
     * One run of the vsync signal, from {@link #startVSync(VSyncRun)} to its stop.
     * The thread of the signal reports through it and ends when {@link #isStopped()} is true.
     * A stopped run reports nothing, so a late thread does not touch the next run.
     */
    protected final class VSyncRun {
        private volatile long lastVSyncNanos = System.nanoTime();

        private VSyncRun() {
        }

        /**
         * Reports a refresh. It does not block.
         *
         * @param frameTimeNanos the time when a frame painted now reaches the display, on the time line of {@link System#nanoTime()}
         * @param refreshPeriodNanos the current refresh period, or 0 when the signal does not know it
         */
        public void onVSync(long frameTimeNanos, long refreshPeriodNanos) {
            if (isStopped()) {
                return;
            }
            lastVSyncNanos = System.nanoTime();
            if (refreshPeriodNanos > 0) {
                periodNanos = clampPeriod(refreshPeriodNanos);
            }
            long refresh = refreshes.incrementAndGet();
            if (hasRequests) {
                postFrame(frameTimeNanos, refresh);
            }
        }

        /**
         * Reports that the signal ended on its own. It does not block.
         * The source then ticks on a timer and tries to start the signal again later.
         */
        public void onFailed() {
            if (isStopped()) {
                return;
            }
            failedRun = this;
            Scheduler.INSTANCE.execute(housekeeper);
        }

        /** Tells whether the source stopped this run. The thread of the signal then ends. */
        public boolean isStopped() {
            return currentRun != this;
        }
    }

    private record Request(Callback callback, long refresh) {
    }

    private record Frame(long timeNanos, long refresh) {
    }

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.DisplayRefreshSource");

    private static final long DEFAULT_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1) / 60;
    private static final long MIN_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1) / 1000;
    private static final long MAX_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1) / 10;
    private static final long IDLE_STOP_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    /** A running signal without a refresh for this time while requests wait is lost, for example on a display that sleeps. */
    private static final long SILENCE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    /** The time before the source tries again to start a vsync signal that did not start or that failed. */
    private static final long START_RETRY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long NO_FRAME = Long.MIN_VALUE;
    private static final long UNKNOWN_PERIOD = -1;

    /** The value {@code false} makes each source tick on a timer. It serves the comparison of the two. */
    private static final boolean VSYNC_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("jbr.displayRefresh.vsync"));

    /** Runs the timer ticks, the idle stop and the restarts of all sources. Its tasks are short and never block. */
    private static final class Scheduler {
        static final ScheduledThreadPoolExecutor INSTANCE = create();

        private static ScheduledThreadPoolExecutor create() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = InnocuousThread.newSystemThread("AWT-DisplayRefresh", task);
                thread.setDaemon(true);
                return thread;
            });
            executor.setKeepAliveTime(10, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }

    private final Object lock = new Object();
    private final Runnable dispatcher = this::dispatch;
    private final Runnable housekeeper = this::housekeep;
    /** The number of refreshes so far. A request waits for a refresh with a higher number than at its time. */
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicReference<Frame> pendingFrame = new AtomicReference<>();

    // guarded by lock
    private ArrayList<Request> requests = new ArrayList<>();
    private boolean listensToDisplay;
    private boolean retired;
    private ScheduledFuture<?> housekeeping;
    private long lastRequestNanos;
    private long nextStartNanos = System.nanoTime();
    private long lastFrameTimeNanos = NO_FRAME;
    private long nominalPeriodNanos = UNKNOWN_PERIOD;

    /** The run of the vsync signal, or null while the source ticks on a timer. It changes only under the lock. */
    private volatile VSyncRun currentRun;
    private volatile VSyncRun failedRun;
    private volatile boolean hasRequests;
    private volatile long periodNanos = DEFAULT_PERIOD_NANOS;

    /**
     * Returns the nominal refresh period of the display, or 0 when the display does not tell it.
     * The source uses it for the timer ticks, and until the first refresh reports the real period.
     * The source calls it under its lock, once after each change of the display configuration.
     */
    protected abstract long nominalPeriodNanos();

    /**
     * Starts the vsync signal: usually a thread that waits for the refreshes and reports them to {@code run}.
     * The source calls it under its lock, so it must not block.
     * The source stops the run by itself, and the thread then ends.
     *
     * @return false when the platform has no vsync signal for this source now; the source then ticks on a timer
     */
    protected abstract boolean startVSync(VSyncRun run);

    /** Runs {@code callback} once on the event dispatch thread at the next refresh. Any thread can call it. */
    public final void request(Callback callback) {
        long now = System.nanoTime();
        synchronized (lock) {
            requests.add(new Request(callback, refreshes.get()));
            hasRequests = true;
            lastRequestNanos = now;
            if (currentRun == null && now - nextStartNanos >= 0) {
                start(now);
            }
            if (housekeeping == null) {
                scheduleHousekeeping(now);
            }
        }
    }

    /**
     * Starts the vsync signal anew. The display that the signal came from can be gone or can have another mode,
     * so a subclass calls it when the platform reports such a change. Any thread can call it. It does not block.
     */
    protected final void restartVSync() {
        Scheduler.INSTANCE.execute(() -> {
            long now = System.nanoTime();
            synchronized (lock) {
                nominalPeriodNanos = UNKNOWN_PERIOD;
                if (currentRun != null) {
                    stop(now, "the display changed");
                }
                nextStartNanos = now;
                if (!requests.isEmpty()) {
                    // the waiting requests get their frame from the new signal, or else from the timer
                    start(now);
                    scheduleHousekeeping(now);
                }
            }
        });
    }

    /**
     * Ends the source of a display that is gone. The source serves its waiting requests on the timer
     * and never starts the vsync signal again. The next requests of a window go to the source of its new display.
     */
    protected final void retire() {
        Scheduler.INSTANCE.execute(() -> {
            long now = System.nanoTime();
            synchronized (lock) {
                retired = true;
                if (currentRun != null) {
                    stop(now, "the display is gone");
                }
                if (!requests.isEmpty()) {
                    scheduleHousekeeping(now);
                }
            }
        });
    }

    @Override
    public final void displayChanged() {
        restartVSync();
    }

    @Override
    public final void paletteChanged() {
    }

    /** The current refresh period: the last one that a refresh reported, or else the nominal one. */
    protected final long getPeriodNanos() {
        return periodNanos;
    }

    // under lock
    private void start(long now) {
        if (!listensToDisplay) {
            listensToDisplay = true;
            // the graphics environment keeps its listeners weakly, and the subclass keeps its sources
            if (GraphicsEnvironment.getLocalGraphicsEnvironment() instanceof SunGraphicsEnvironment environment) {
                environment.addDisplayChangedListener(this);
            }
        }
        if (nominalPeriodNanos == UNKNOWN_PERIOD) {
            nominalPeriodNanos = nominalPeriodNanos();
        }
        periodNanos = clampPeriod(nominalPeriodNanos);
        if (!retired && VSYNC_ENABLED) {
            // the run is current before its thread starts, so the thread does not see it as stopped
            VSyncRun run = new VSyncRun();
            currentRun = run;
            if (startVSync(run)) {
                trace("the vsync signal started");
                return;
            }
            currentRun = null;
        }
        nextStartNanos = now + START_RETRY_NANOS;
        trace("no vsync signal, the source ticks on a timer");
    }

    // under lock
    private void stop(long now, String reason) {
        currentRun = null;
        nextStartNanos = now;
        trace("the vsync signal stopped, " + reason);
    }

    /** The refreshes that come before the event dispatch thread takes the frame merge into one event. */
    private void postFrame(long frameTimeNanos, long refresh) {
        if (pendingFrame.getAndSet(new Frame(frameTimeNanos, refresh)) == null) {
            EventQueue.invokeLater(dispatcher);
        }
    }

    private void dispatch() {
        Frame frame = pendingFrame.getAndSet(null);
        if (frame == null) {
            return;
        }
        ArrayList<Request> due = new ArrayList<>();
        long frameTimeNanos = frame.timeNanos();
        synchronized (lock) {
            // a request that came after this refresh, for example from a callback of the previous frame, waits for the next one
            ArrayList<Request> waiting = new ArrayList<>();
            for (Request request : requests) {
                (request.refresh() < frame.refresh() ? due : waiting).add(request);
            }
            requests = waiting;
            hasRequests = !waiting.isEmpty();
            if (due.isEmpty()) {
                return;
            }
            // a timer tick after a vsync tick must not move the time back
            if (frameTimeNanos - lastFrameTimeNanos < 0 && lastFrameTimeNanos != NO_FRAME) {
                frameTimeNanos = lastFrameTimeNanos;
            }
            lastFrameTimeNanos = frameTimeNanos;
        }
        long period = periodNanos;
        for (Request request : due) {
            try {
                request.callback().onFrame(frameTimeNanos, period);
            } catch (Throwable t) {
                // one failed callback must not drop the frame of the others
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
            }
        }
    }

    /**
     * The timer tick, the check of the signal and the idle stop. It runs on the scheduler thread
     * for as long as the source has a request or a running vsync signal.
     */
    private void housekeep() {
        long now = System.nanoTime();
        synchronized (lock) {
            VSyncRun run = currentRun;
            if (run != null && failedRun == run) {
                stop(now, "the signal failed");
                nextStartNanos = now + START_RETRY_NANOS;
            } else if (run != null && !requests.isEmpty() && now - run.lastVSyncNanos >= SILENCE_NANOS) {
                stop(now, "the signal is silent");
                nextStartNanos = now + START_RETRY_NANOS;
            }
            if (requests.isEmpty()) {
                if (currentRun != null && now - lastRequestNanos >= IDLE_STOP_NANOS) {
                    stop(now, "no requests");
                }
                if (currentRun == null) {
                    cancelHousekeeping();
                    return;
                }
            } else if (currentRun == null) {
                // the frame time is the period boundary, so that a late wake-up of the timer does not shake the animation
                postFrame(now - Math.floorMod(now, periodNanos), refreshes.incrementAndGet());
            }
            scheduleHousekeeping(now);
        }
    }

    /**
     * With a running vsync signal the next run is a check of the signal and of the idle stop.
     * Without it the next run is a timer tick, on the next boundary of the nominal period.
     */
    private void scheduleHousekeeping(long now) {
        long delay;
        if (currentRun != null) {
            delay = requests.isEmpty() ? IDLE_STOP_NANOS : SILENCE_NANOS;
        } else {
            long period = periodNanos;
            delay = period - Math.floorMod(now, period);
        }
        cancelHousekeeping();
        housekeeping = Scheduler.INSTANCE.schedule(housekeeper, delay, TimeUnit.NANOSECONDS);
    }

    private void cancelHousekeeping() {
        if (housekeeping != null) {
            housekeeping.cancel(false);
            housekeeping = null;
        }
    }

    private void trace(String message) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(this + ": " + message);
        }
    }

    private static long clampPeriod(long periodNanos) {
        if (periodNanos <= 0) {
            return DEFAULT_PERIOD_NANOS;
        }
        return Math.max(MIN_PERIOD_NANOS, Math.min(MAX_PERIOD_NANOS, periodNanos));
    }

    /** Converts a refresh rate of a {@link java.awt.DisplayMode} to a period. An unknown rate gives 0. */
    public static long periodOfRefreshRate(int refreshRateHz) {
        return refreshRateHz > 0 ? TimeUnit.SECONDS.toNanos(1) / refreshRateHz : 0;
    }
}
