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

import sun.util.logging.PlatformLogger;

/**
 * Entry point of the experimental Skia rendering backend (JBR-10654).
 * <p>
 * The backend is opt-in: it is only initialized when {@code -Dsun.java2d.skia=true}
 * is set and the runtime was built with {@code --with-skia}. Initialization loads
 * {@code libawt_skia} and checks that the platform GPU API is available; the
 * Graphite contexts are created per graphics configuration by the backend.
 */
public final class SKEnvironment {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.skia.SKEnvironment");

    private static final boolean REQUESTED =
            "true".equalsIgnoreCase(System.getProperty("sun.java2d.skia", ""));
    private static final boolean VERBOSE =
            Boolean.getBoolean("sun.java2d.skia.verbose");
    /** Who rasterizes antialiased shapes: {@code marlin} (coverage tiles, the default) or {@code skia}. */
    private static final String SHAPES =
            System.getProperty("sun.java2d.skia.shapes", "marlin");

    private static boolean initialized;
    private static String description;

    private SKEnvironment() {}

    /**
     * Whether {@code -Dsun.java2d.skia=true} was given; the platform flags read
     * this before any library is loaded. Whether the backend is actually
     * available is {@link #isSkiaEnabled()}.
     */
    public static boolean isSkiaRequested() {
        return REQUESTED;
    }

    /**
     * Returns whether the Skia backend was requested and the GPU API is
     * available. Triggers initialization on first call.
     */
    public static synchronized boolean isSkiaEnabled() {
        init();
        return description != null;
    }

    /** Whether antialiased shapes are rasterized by Skia rather than by Marlin. */
    public static boolean rasterizesShapes() {
        return "skia".equalsIgnoreCase(SHAPES);
    }

    /**
     * Human-readable description of the GPU API, device and Skia milestone,
     * or {@code null} if the backend is not enabled.
     */
    public static synchronized String getDescription() {
        init();
        return description;
    }

    @SuppressWarnings("restricted")
    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!REQUESTED) {
            return;
        }
        try {
            System.loadLibrary("awt_skia");
            description = initNative();
        } catch (UnsatisfiedLinkError e) {
            if (VERBOSE) {
                System.err.println("Skia backend is not available: " + e);
            }
            log.warning("Skia backend is not available", e);
            description = null;
        }
        if (VERBOSE || log.isLoggable(PlatformLogger.Level.FINE)) {
            String msg = "Skia rendering enabled: " + (description != null ? "YES (" + description + ", shapes: " + SHAPES + ")" : "NO");
            if (VERBOSE) {
                System.err.println(msg);
            }
            log.fine(msg);
        }
    }

    /** Describes the GPU API and device Skia will run on; {@code null} if unavailable. */
    private static native String initNative();
}
