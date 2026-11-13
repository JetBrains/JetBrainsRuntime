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

package sun.awt.windows;

import jdk.internal.misc.InnocuousThread;
import sun.awt.DisplayChangedListener;
import sun.awt.DisplayRefreshSource;
import sun.awt.Win32GraphicsDevice;
import sun.java2d.SunGraphicsEnvironment;

import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The refreshes of one Windows monitor, from {@code IDXGIOutput::WaitForVBlank} on a thread of the source.
 * <p>
 * The signal does not depend on the rendering pipeline: it works with D3D, OpenGL and GDI.
 * A remote session has no vertical blank, so the source then ticks on a timer.
 */
final class WDisplayRefreshSource extends DisplayRefreshSource {
    /** A wait that returns sooner than this did not wait for a vertical blank. */
    private static final long MIN_WAIT_NANOS = TimeUnit.MICROSECONDS.toNanos(500);
    /** After this number of such waits in a row, the output has no vertical blank: a virtual or a sleeping monitor. */
    private static final int MAX_EARLY_RETURNS = 8;

    private static final Map<Integer, WDisplayRefreshSource> SOURCES = new HashMap<>();

    /** Retires the sources of the monitors that are gone. The graphics environment keeps it weakly. */
    private static final DisplayChangedListener DISPLAYS = new DisplayChangedListener() {
        @Override
        public void displayChanged() {
            retireRemovedScreens();
        }

        @Override
        public void paletteChanged() {
        }
    };

    static {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment() instanceof SunGraphicsEnvironment environment) {
            environment.addDisplayChangedListener(DISPLAYS);
        }
    }

    private final int screen;

    private WDisplayRefreshSource(int screen) {
        this.screen = screen;
    }

    /** Returns the source of the monitor that shows {@code window}, or of the primary monitor for null. */
    static DisplayRefreshSource of(Window window) {
        GraphicsConfiguration gc = window != null ? window.getGraphicsConfiguration() : null;
        GraphicsDevice device = gc != null ? gc.getDevice() : null;
        if (device == null) {
            device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        }
        int screen = ((Win32GraphicsDevice) device).getScreen();
        synchronized (SOURCES) {
            return SOURCES.computeIfAbsent(screen, WDisplayRefreshSource::new);
        }
    }

    /** Retires the sources of the screen indexes that are gone. */
    private static void retireRemovedScreens() {
        int screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length;
        synchronized (SOURCES) {
            SOURCES.values().removeIf(source -> {
                if (source.screen < screens) {
                    return false;
                }
                source.retire();
                return true;
            });
        }
    }

    /** The screen index names another monitor after a change of the display configuration, so it reads the mode anew. */
    @Override
    protected long nominalPeriodNanos() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (screen >= devices.length) {
            return 0;
        }
        DisplayMode mode = devices[screen].getDisplayMode();
        return mode != null ? periodOfRefreshRate(mode.getRefreshRate()) : 0;
    }

    @Override
    protected boolean startVSync(VSyncRun run) {
        if (nativeIsRemoteSession()) {
            return false;
        }
        Thread thread = InnocuousThread.newSystemThread("AWT-VBlank-" + screen, () -> tick(run));
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    @Override
    public String toString() {
        return "WDisplayRefreshSource[screen " + screen + "]";
    }

    /** The thread of the signal: it owns the output, and it ends after its current wait when the run stops. */
    private void tick(VSyncRun run) {
        long output = nativeOpenOutput(screen);
        if (output == 0) {
            run.onFailed();
            return;
        }
        try {
            int earlyReturns = 0;
            while (!run.isStopped()) {
                long before = System.nanoTime();
                if (!nativeWaitForVBlank(output)) {
                    run.onFailed();
                    return;
                }
                long now = System.nanoTime();
                if (now - before >= MIN_WAIT_NANOS) {
                    earlyReturns = 0;
                } else if (++earlyReturns >= MAX_EARLY_RETURNS) {
                    run.onFailed();
                    return;
                }
                // DWM shows the content painted after this vertical blank at a later one
                run.onVSync(now + getPeriodNanos(), 0);
            }
        } finally {
            nativeCloseOutput(output);
        }
    }

    private static native boolean nativeIsRemoteSession();

    /** Returns the {@code IDXGIOutput} of the monitor with the index {@code screen}, or 0 when DXGI has none. */
    private static native long nativeOpenOutput(int screen);

    /** Blocks until the next vertical blank of the output. Returns false when the output is not valid anymore. */
    private static native boolean nativeWaitForVBlank(long output);

    private static native void nativeCloseOutput(long output);
}
