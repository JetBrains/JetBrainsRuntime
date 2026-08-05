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
        if (clock == null) {
            long period = refreshPeriodNanos(displayId);
            clock = new DisplayClock(this, displayId, period > 0 ? period : FALLBACK_PERIOD_NANOS);
            clocks.put(displayId, clock);
        }

        if (TRACE) trace("subscribe display=" + displayId);
        return new Subscription(this, clock, listener);
    }

    private synchronized void unsubscribe(DisplayClock clock, Listener listener) {
        if (clock.remove(listener)) {
            clocks.remove(clock.displayId, clock);
        }

        if (TRACE) trace("unsubscribe display=" + clock.displayId);
    }

    /**
     * Called by a clock (from its tick thread's hotplug check) when its
     * display disappeared. Removing the stopped clock from the registry is
     * what makes display replug work: ids are commonly reused when the same
     * monitor returns, and a stale entry would hand later subscribers a clock
     * that never ticks. Closing the dead clock's remaining subscriptions
     * afterwards is a harmless no-op.
     */
    private synchronized void displayGone(DisplayClock clock) {
        clocks.remove(clock.displayId, clock);
        if (TRACE) trace("display gone, clock stopped display=" + clock.displayId);
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

    private static void trace(String message) {
        if (TRACE) {
            System.err.println("[JBR-FramePacing] " + message);
        }
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

        private Subscription(FramePacing service, DisplayClock clock, Listener listener) {
            this.service = service;
            this.clock = clock;
            this.listenerRef = new WeakReference<>(listener);
            clock.add(listener);
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
            Listener listener = listenerRef.get();
            if (listener != null) {
                service.unsubscribe(clock, listener);
            }
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

        DisplayClock(FramePacing service, long displayId, long periodNanos) {
            this.service = service;
            this.displayId = displayId;
            this.periodNanos = periodNanos;
            this.hotplugCheckTicks = Math.max(1, 1_000_000_000L / periodNanos);
        }

        void add(Listener listener) {
            boolean first = listenerRefs.isEmpty();
            listenerRefs.add(new WeakReference<>(listener));

            if (first) {
                Thread thread = new Thread(this, "JBR-FramePacing-" + displayId);
                thread.setDaemon(true);
                thread.start();
                if (TRACE) trace("clock started display=" + displayId + " period=" + periodNanos + "ns");
            }
        }

        /**
         * @return true when the last listener was removed and the clock stopped
         */
        boolean remove(Listener listener) {
            for (var listenerRef : listenerRefs) {
                if (listenerRef.get() == listener) {
                    listenerRefs.remove(listenerRef);
                    break;
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
                        }
                    } catch (Throwable e) {
                        System.err.println("FramePacing clock listener threw exception: " + e);
                    }
                }

                ticks++;
                if (ticks % hotplugCheckTicks == 0 && !service.isDisplayPresent(displayId)) {
                    stopped = true;
                    service.displayGone(this);
                }
            }
            if (TRACE) trace("clock stopped display=" + displayId);
        }
    }

    private static void trace(String message) {
        System.err.println("[JBR-FramePacing] " + message);
    }
}
