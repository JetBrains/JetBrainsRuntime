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
import com.jetbrains.exported.JBRApiSupport;
import jdk.internal.misc.InnocuousThread;
import sun.util.logging.PlatformLogger;

import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

/**
 * Display-aligned frame pacing clock. Delivers asynchronous ticks for
 * rate-limiting UI work; not a present-completion or drawable callback.
 *
 * <p>Shared ESTIMATED backend: a high-resolution timer aligned to the display
 * refresh rate, one refcounted daemon thread per subscribed display. Platform
 * subclasses resolve stable display ids from toolkit devices; native clock
 * backends (CVDisplayLink, DWM composition timing) are follow-up work behind
 * the same surface.
 *
 * <p>Delivery semantics: missed ticks are skipped, never queued. Listeners are
 * invoked sequentially on the clock thread; exceptions are isolated per
 * listener. {@code Subscription.close()} is idempotent and non-blocking — no
 * new deliveries start after it returns, but one in-flight delivery may
 * complete.
 */
public abstract class FramePacing {

    public static final int QUALITY_NONE = 0;
    public static final int QUALITY_ESTIMATED = 1;
    public static final int QUALITY_COMPOSITION_CLOCK = 2;
    public static final int QUALITY_DISPLAY_LINK = 3;

    /**
     * Fallback period in nanoseconds: 60 Hz.
     */
    private static final long FALLBACK_PERIOD_NANOS = 1_000_000_000L / 60;
    private static final boolean TRACE = Boolean.getBoolean("jbr.framePacing.trace");
    private static final PlatformLogger LOGGER = PlatformLogger.getLogger("JBR-FramePacing");

    private final Map<Long, DisplayClock> clocks = new HashMap<>();

    protected FramePacing() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new JBRApi.ServiceNotAvailableException("FramePacing is not available in a headless environment");
        }

        if (TRACE) trace("service created");
    }

    /**
     * Resolves a stable toolkit id for the given screen device, or -1 if the
     * device cannot be identified. The id must be stable while the display
     * stays connected, including across GraphicsConfiguration recreation.
     */
    protected abstract long deviceId(GraphicsDevice device);

    public int getQuality() {
        return QUALITY_ESTIMATED;
    }

    public long displayId(GraphicsConfiguration gc) {
        if (gc == null) return -1;

        GraphicsDevice device = gc.getDevice();
        if (device == null || device.getType() != GraphicsDevice.TYPE_RASTER_SCREEN) return -1;

        return deviceId(device);
    }

    public long refreshPeriodNanos(long displayId) {
        GraphicsDevice device = findDevice(displayId);
        if (device == null) return 0;

        DisplayMode mode = device.getDisplayMode();
        if (mode == null) return 0;

        int rate = mode.getRefreshRate();
        if (rate <= 0) return 0;

        return 1_000_000_000L / rate;
    }

    public synchronized Subscription subscribe(long displayId, Listener listener) {
        if (listener == null) throw new NullPointerException("The listener cannot be null");
        if (!isDisplayPresent(displayId)) return null;

        DisplayClock clock = clocks.get(displayId);
        if (clock == null || clock.stopped) {
            // A stopped clock can still be registered, because a clock stopped
            // by its last unsubscription is retired by that caller rather than
            // by itself. Adopting one would hand back a Subscription whose
            // deliveries are already short-circuited, so it would never tick.
            // Replacing the entry is safe: a clock is only ever retired with a
            // value-matching remove, which no longer matches once replaced.
            long period = refreshPeriodNanos(displayId);
            clock = new DisplayClock(this, displayId, period > 0 ? period : FALLBACK_PERIOD_NANOS);
            clocks.put(displayId, clock);
        }

        if (TRACE) trace("subscribe display=" + displayId);
        return new Subscription(this, clock, listener);
    }

    private synchronized void unsubscribe(DisplayClock clock, Listener listener) {
        if (clock.remove(listener)) {
            removeClock(clock);
        }

        if (TRACE) trace("unsubscribe display=" + clock.displayId);
    }

    /**
     * Removes a stopped clock from the registry, on the last unsubscription or
     * when the clock stops itself (display disappeared, or every listener was
     * GCed). Prompt removal is what makes display replug work: ids are
     * commonly reused when the same monitor returns, and a stale entry would
     * hand later subscribers a clock that never ticks. Closing a dead clock's
     * remaining subscriptions afterward is a harmless no-op.
     */
    private synchronized void removeClock(DisplayClock clock) {
        clocks.remove(clock.displayId, clock);
        if (TRACE) trace("clock removed display=" + clock.displayId);
    }

    /**
     * Retires a clock that its own tick thread believes is idle, stopping it
     * and removing it from the registry as one step under the service lock.
     * The tick thread evaluates that condition without the lock, so a
     * subscriber can arrive in between; the condition is therefore re-checked
     * here, and the clock keeps running when one did.
     *
     * @return true when the clock was stopped and retired
     */
    private synchronized boolean retireIfIdle(DisplayClock clock) {
        if (!clock.listenerRefs.isEmpty() && isDisplayPresent(clock.displayId)) {
            return false;
        }

        clock.stopped = true;
        removeClock(clock);
        return true;
    }

    private boolean isDisplayPresent(long displayId) {
        return findDevice(displayId) != null;
    }

    private GraphicsDevice findDevice(long displayId) {
        if (displayId == -1) return null;

        GraphicsDevice[] screenDevices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (GraphicsDevice device : screenDevices) {
            if (device.getType() != GraphicsDevice.TYPE_RASTER_SCREEN) continue;
            if (deviceId(device) == displayId) return device;
        }
        return null;
    }

    @JBRApi.Provided("FramePacing.Listener")
    public interface Listener {
        void onTick(long displayId, long timeNanos);
    }

    @JBRApi.Provides("FramePacing.Subscription")
    public static final class Subscription implements AutoCloseable {
        private final FramePacing service;
        private final DisplayClock clock;
        private final WeakReference<Listener> listenerRef;
        private volatile boolean closed;

        /**
         * A listener that arrived through the JBR API boundary is a generated
         * proxy wrapper that nothing else references (the wrapper holds the
         * client's listener, never the other way around), so a plain weak
         * reference to it would be collected at the first GC no matter what
         * the client keeps alive. For those, this subscription — the client's
         * only handle — pins the wrapper until close() or until the client
         * abandons the subscription itself. Direct in-JBR listeners are only
         * weakly referenced, so their lifetime follows the listener object.
         */
        @SuppressWarnings("unused")
        private Listener proxyPin;

        private Subscription(FramePacing service, DisplayClock clock, Listener listener) {
            this.service = service;
            this.clock = clock;
            proxyPin = (listener instanceof JBRApiSupport.Proxy) ? listener : null;
            listenerRef = new WeakReference<>(listener);
            clock.add(listenerRef);
        }

        public long displayId() {
            return clock.displayId;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }

            // The pin is released only after the unsubscription: it may hold the
            // only strong reference to the listener, and clearing it first would
            // let a badly timed GC empty listenerRef before the unsubscription
            // can use it.
            Listener listener = listenerRef.get();
            if (listener != null) {
                service.unsubscribe(clock, listener);
            }
            proxyPin = null;
        }
    }

    /**
     * One refcounted tick source per display. The thread starts with the
     * first listener and exits when the last one is removed or the display
     * disappears. Hotplug is checked roughly once per second.
     */
    private static final class DisplayClock implements Runnable {
        final long displayId;

        private final FramePacing service;
        private final long periodNanos;
        private final long hotplugCheckTicks;
        private final CopyOnWriteArrayList<WeakReference<Listener>> listenerRefs = new CopyOnWriteArrayList<>();

        private volatile boolean stopped;
        private boolean started;

        DisplayClock(FramePacing service, long displayId, long periodNanos) {
            this.service = service;
            this.displayId = displayId;
            this.periodNanos = periodNanos;
            this.hotplugCheckTicks = Math.max(1, 1_000_000_000L / periodNanos);
        }

        void add(WeakReference<Listener> listenerRef) {
            // Deliberately not "the listener list is empty": the tick thread
            // sweeps collected listeners out of that list itself, so an empty
            // list means nobody is listening right now, not that no thread is
            // running yet. Starting on that would give one display two tick
            // threads. Every caller holds the service lock, so a plain field
            // is enough here.
            boolean first = !started;
            started = true;
            listenerRefs.add(listenerRef);

            if (first) {
                Thread thread = InnocuousThread.newSystemThread("JBR-FramePacing-" + displayId, this);
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                thread.start();
                if (TRACE) trace("clock started display=" + displayId + " period=" + periodNanos + "ns");
            }
        }

        /**
         * @return true when the last listener was removed and the clock stopped
         */
        boolean remove(Listener listener) {
            for (var listenerRef : listenerRefs) {
                Listener l = listenerRef.get();
                if (l == listener) {
                    listenerRefs.remove(listenerRef);
                    break;
                } else if (l == null) {
                    // Clean up stale references
                    listenerRefs.remove(listenerRef);
                }
            }

            if (listenerRefs.isEmpty()) {
                stopped = true;
                return true;
            }
            return false;
        }

        @Override
        public void run() {
            // Phase-aligned wait loop: park until the next period boundary,
            // then deliver. parkNanos may wake early or spuriously, so every
            // wake re-checks the deadline and re-parks for the remainder —
            // tick timing is gated by the monotonic-clock comparison, not by
            // park precision (which is only as good as the OS scheduler).
            long deadline = System.nanoTime() + periodNanos;
            long ticks = 0;

            while (!stopped) {
                long now = System.nanoTime();
                if (now < deadline) {
                    LockSupport.parkNanos(deadline - now);
                    continue;
                }

                // Woke past the deadline: advance it to the next future
                // period boundary, skipping any fully missed periods rather
                // than delivering catch-up bursts.
                deadline += ((now - deadline) / periodNanos + 1) * periodNanos;

                for (WeakReference<Listener> listenerRef : listenerRefs) {
                    try {
                        Listener listener = listenerRef.get();
                        if (listener != null) {
                            listener.onTick(displayId, now);
                        } else {
                            // Clean up stale references
                            listenerRefs.remove(listenerRef);
                        }
                    } catch (Throwable e) {
                        LOGGER.severe("FramePacing clock listener threw an exception", e);
                    }
                }

                ticks++;
                // Only a candidate: this is evaluated without the service
                // lock, so a subscriber may be arriving right now. retireIfIdle
                // re-checks under the lock and stops the clock only if none did.
                if (ticks % hotplugCheckTicks == 0 && !service.isDisplayPresent(displayId) || listenerRefs.isEmpty()) {
                    service.retireIfIdle(this);
                }
            }
            if (TRACE) trace("clock stopped display=" + displayId);
        }
    }

    static void trace(String message) {
        LOGGER.info(message);
    }

    protected static long makePositive(long value) {
        return value & 0xFFFFFFFFL;
    }
}
