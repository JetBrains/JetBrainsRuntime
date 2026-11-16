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
 * The Skia rendering backend's switches and start. The backend is off unless
 * {@code -Dsun.java2d.skia=true} is given and the runtime was built with it
 * ({@code --with-skia}); it renders Java2D through Skia's Graphite — on Windows
 * over Dawn and Direct3D 11 — into its own window surfaces. The backend is Windows-only.
 * <p>
 * The switches, all under {@code sun.java2d.skia}:
 * <ul>
 * <li>{@code sun.java2d.skia=true|false}: the backend on or off (default off).</li>
 * <li>{@code sun.java2d.skia.verbose=true}: what was chosen and why, on stderr
 *     (as {@code sun.java2d.vulkan=True} and {@code sun.java2d.d3d=True} report).</li>
 * </ul>
 * <p>
 * The backend starts on the first call of {@link #isSkiaEnabled} or {@link #runningOn()},
 * as the Vulkan and Direct3D pipelines start on their first query ({@code VKEnv},
 * {@code D3DGraphicsDevice}): the precedence over the other pipelines asks first when
 * one of them is requested too ({@code WindowsFlags}, Vulkan's start in
 * {@code WToolkit}), the screen devices' configurations ({@code Win32GraphicsDevice})
 * otherwise, so that the start happens once, early, and before any surface is created.
 * A start that fails leaves the other pipelines as requested.
 */
public final class SKEnvironment {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.skia.SKEnvironment");

    private static final boolean REQUESTED = "true".equalsIgnoreCase(System.getProperty("sun.java2d.skia"));
    private static final boolean VERBOSE = Boolean.getBoolean("sun.java2d.skia.verbose");

    /**
     * The side of the backend's glyph cache textures in texels: the backend's default (1024,
     * SKConfiguration.cpp) unless {@code sun.java2d.skia.glyphCacheTexels} names another, 32 to 16384
     * in steps of 32 (anything else keeps the default). The property is for the backend's tests only
     * (test/jdk/jb/java/awt/Graphics2D), which shrink the atlas to make its evictions deterministic;
     * this package is not exported.
     */
    private static final int GLYPH_CACHE_TEXELS = Integer.getInteger("sun.java2d.skia.glyphCacheTexels", 0);

    /**
     * Set once {@link #startOnce} ran, whether the backend started: a start is not
     * retried. Written after {@link #runningOn}, so that a reader who sees it sees the outcome.
     */
    private static volatile boolean startAttempted;

    /**
     * What the backend renders on, once it started: the Skia release, the graphics API and
     * the adapter, as the platform describes them ({@code "Skia m156 Graphite/Dawn on NVIDIA
     * GeForce ... (D3D11)"}); {@code null} before the start and when it did not start.
     */
    private static volatile String runningOn;

    private SKEnvironment() {}

    /**
     * Whether the Skia backend renders; starts it on the first call (loads libawt_skia and
     * creates the GPU device).
     */
    public static boolean isSkiaEnabled() {
        return runningOn() != null;
    }

    /**
     * What the backend renders on — the Skia release, the graphics API and the adapter — or
     * {@code null} if it is not enabled; starts the backend on the first call, as
     * {@link #isSkiaEnabled} does.
     */
    public static String runningOn() {
        if (!startAttempted) {
            startOnce();
        }
        return runningOn;
    }

    private static synchronized void startOnce() {
        if (startAttempted) {
            return;
        }
        try {
            if (REQUESTED) {
                runningOn = start();
            }
        } finally {
            startAttempted = true;
        }
    }

    /**
     * Loads libawt_skia, hands it the configuration, and starts Graphite
     * on the platform's graphics API.
     *
     * @return what the backend runs on ({@link #runningOn()}), or {@code null} if it did not start
     */
    private static String start() {
        String startedOn = null;
        try {
            if (loadLibrary()) {
                // before the start: the API creates its device as it starts, and the device takes
                // the configuration then
                configureNative(GLYPH_CACHE_TEXELS);
                startedOn = startNative();
            }
        } catch (RuntimeException | LinkageError failure) {
            // called while the GraphicsEnvironment is constructed: nothing from here may keep
            // it from starting; the platform's default pipeline renders instead
            warn("Skia backend is not available: " + failure);
        }
        if (VERBOSE || log.isLoggable(PlatformLogger.Level.FINE)) {
            report("Skia rendering enabled: " + (startedOn != null
                    ? "YES (" + startedOn + ")"
                    : "NO"));
        }
        return startedOn;
    }

    @SuppressWarnings("restricted")
    private static boolean loadLibrary() {
        try {
            System.loadLibrary("awt_skia");
            return true;
        } catch (UnsatisfiedLinkError error) {
            // the flag on a runtime built without Skia: a notice, no stack trace
            warn("Skia library is not available: " + error.getMessage());
            return false;
        }
    }

    /** What was chosen and why: on stderr with {@code verbose}, and at FINE. */
    static void report(String message) {
        if (VERBOSE) {
            System.err.println(message);
        }
        log.fine(message);
    }

    /** What keeps something from working: on stderr with {@code verbose}, and at WARNING. */
    static void warn(String message) {
        if (VERBOSE) {
            System.err.println(message);
        }
        log.warning(message);
    }

    /**
     * Starts the platform's graphics API and creates its device; what the backend runs on then
     * ({@link #runningOn}), or {@code null} if the API finds no adapter or its device does not start.
     */
    private static native String startNative();

    /** Hands the configuration to the native backend before any context exists. */
    private static native void configureNative(int glyphCacheTexels);

    // ---- diagnostics, for the backend's tests (test/jdk/jb/java/awt/Graphics2D) ----------------------

    /** The device's features as the platform reports them. */
    @SuppressWarnings("unused")
    public static String deviceFeatures() {
        return isSkiaEnabled() ? deviceFeaturesNative() : "";
    }

    private static native String deviceFeaturesNative();

    /** The glyph cache's counters (SKGlyphCache.h). */
    @SuppressWarnings("unused")
    public static long[] glyphCacheCounters() {
        return isSkiaEnabled() ? glyphCacheCountersNative() : new long[0];
    }

    private static native long[] glyphCacheCountersNative();

}
