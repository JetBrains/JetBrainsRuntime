/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Frame;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import sun.awt.AWTAccessor;
import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;

/**
 * The surfaces of windows on Windows, for the Skia backend's tests of which pipeline a
 * window renders on. A window's peer gets its surface after the window is shown and
 * replaces it on a display change, both asynchronously, so the tests poll it. A test
 * that uses it names {@code java.desktop/sun.awt}, {@code java.desktop/sun.awt.windows}
 * and {@code java.desktop/sun.java2d} in its {@code @modules}.
 */
public final class WindowSurfaces {

    /** How long a window's surface may take to become what a test expects. */
    public static final long TIMEOUT_MS = 5000;

    /** How often the surface is polled. */
    private static final long POLL_INTERVAL_MS = 50;

    /** The surface of a window whose peer has none yet. */
    public static final String NONE = "none";

    /** The size of the frame {@link #ofNewFrame} shows: small, but a real window with a surface. */
    private static final int FRAME_WIDTH = 200;
    private static final int FRAME_HEIGHT = 100;

    private WindowSurfaces() {
    }

    /** The window's surface data, or null before its peer has one. */
    public static SurfaceData surfaceData(Component window) {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(window);
        return peer instanceof WComponentPeer componentPeer ? componentPeer.getSurfaceData() : null;
    }

    /** The class name of the window's surface data, {@link #NONE} before its peer has one. */
    public static String of(Component window) {
        SurfaceData surfaceData = surfaceData(window);
        return surfaceData == null ? NONE : surfaceData.getClass().getName();
    }

    /** Polls the window's surface until it is what the test expects or the timeout passes; the surface last seen. */
    public static String await(Component window, Predicate<String> expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        String surface = of(window);
        while (!expected.test(surface) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
            surface = of(window);
        }
        return surface;
    }

    /** Shows a frame, waits up to {@link #TIMEOUT_MS} for its surface to be what the test expects, disposes it; the surface last seen. */
    public static String ofNewFrame(String title, Predicate<String> expected) throws Exception {
        AtomicReference<Frame> frameReference = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame(title);
            frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
            frame.setVisible(true);
            frameReference.set(frame);
        });
        try {
            return await(frameReference.get(), expected, TIMEOUT_MS);
        } finally {
            EventQueue.invokeAndWait(() -> frameReference.get().dispose());
        }
    }
}
