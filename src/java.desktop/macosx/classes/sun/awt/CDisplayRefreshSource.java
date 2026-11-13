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

import jdk.internal.misc.InnocuousThread;
import sun.java2d.SunGraphicsEnvironment;

import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The refreshes of one macOS display, from a {@code CVDisplayLink} of that display.
 * <p>
 * The link is separate from the link of the Metal pipeline, so it works with each rendering pipeline
 * and does not change the presentation of frames. It exists only while frame requests come.
 * The callback of the link makes no JNI call: it wakes a thread of the source, and that thread reports the refresh.
 */
public final class CDisplayRefreshSource extends DisplayRefreshSource {
    /** The longest wait for one refresh. The thread of the signal checks for its stop after each wait. */
    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long NO_REFRESH = Long.MIN_VALUE;

    private static final Map<Integer, CDisplayRefreshSource> SOURCES = new HashMap<>();

    /** Retires the sources of the displays that are gone. The graphics environment keeps it weakly. */
    private static final DisplayChangedListener DISPLAYS = new DisplayChangedListener() {
        @Override
        public void displayChanged() {
            retireRemovedDisplays();
        }

        @Override
        public void paletteChanged() {
        }
    };

    static {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment() instanceof SunGraphicsEnvironment environment) {
            environment.addDisplayChangedListener(DISPLAYS);
        }
        nativeObserveWake();
    }

    private final int displayID;

    private CDisplayRefreshSource(int displayID) {
        this.displayID = displayID;
    }

    /** Returns the source of the display that shows {@code window}, or of the main display for null. */
    public static DisplayRefreshSource of(Window window) {
        GraphicsConfiguration gc = window != null ? window.getGraphicsConfiguration() : null;
        GraphicsDevice device = gc != null ? gc.getDevice() : null;
        if (device == null) {
            device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        }
        int displayID = ((CGraphicsDevice) device).getDisplayID();
        synchronized (SOURCES) {
            return SOURCES.computeIfAbsent(displayID, CDisplayRefreshSource::new);
        }
    }

    private static void retireRemovedDisplays() {
        Set<Integer> present = new HashSet<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device instanceof CGraphicsDevice cgDevice) {
                present.add(cgDevice.getDisplayID());
            }
        }
        synchronized (SOURCES) {
            SOURCES.values().removeIf(source -> {
                if (present.contains(source.displayID)) {
                    return false;
                }
                source.retire();
                return true;
            });
        }
    }

    // Called from native code on the main thread, after the system or the displays wake up.
    private static void onWake() {
        synchronized (SOURCES) {
            for (CDisplayRefreshSource source : SOURCES.values()) {
                source.restartVSync();
            }
        }
    }

    @Override
    protected long nominalPeriodNanos() {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device instanceof CGraphicsDevice cgDevice && cgDevice.getDisplayID() == displayID) {
                DisplayMode mode = cgDevice.getDisplayMode();
                return mode != null ? periodOfRefreshRate(mode.getRefreshRate()) : 0;
            }
        }
        return 0;
    }

    @Override
    protected boolean startVSync(VSyncRun run) {
        Thread thread = InnocuousThread.newSystemThread("AWT-DisplayLink-" + displayID, () -> tick(run));
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** The thread of the signal: it owns the link from its creation to its release. */
    private void tick(VSyncRun run) {
        long link = nativeOpen(displayID);
        if (link == 0) {
            run.onFailed();
            return;
        }
        try {
            while (!run.isStopped()) {
                long nanosUntilOutput = nativeWaitForRefresh(link, WAIT_NANOS);
                if (nanosUntilOutput != NO_REFRESH) {
                    run.onVSync(System.nanoTime() + nanosUntilOutput, nativeRefreshPeriod(link));
                }
            }
        } finally {
            nativeClose(link);
        }
    }

    @Override
    public String toString() {
        return "CDisplayRefreshSource[display " + displayID + "]";
    }

    /** Calls {@link #onWake()} after each wake of the system or of the displays. */
    private static native void nativeObserveWake();

    /** Creates and starts a {@code CVDisplayLink}. Returns 0 when the display has none, for example after its removal. */
    private static native long nativeOpen(int displayID);

    /**
     * Waits for the next refresh of the link, at most {@code timeoutNanos}.
     * Returns the time from now until the frame reaches the display, or {@link #NO_REFRESH} after the timeout.
     */
    private static native long nativeWaitForRefresh(long link, long timeoutNanos);

    /** Returns the refresh period of the last refresh, or 0 when the link does not know it. */
    private static native long nativeRefreshPeriod(long link);

    /** Stops and releases the link. */
    private static native void nativeClose(long link);
}
