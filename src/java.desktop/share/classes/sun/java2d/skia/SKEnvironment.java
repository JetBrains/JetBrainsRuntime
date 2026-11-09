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

import java.util.Locale;
import sun.util.logging.PlatformLogger;

/**
 * The Skia rendering backend's switches and start. The backend is off unless
 * {@code -Dsun.java2d.skia=true} is given and the runtime was built with it
 * ({@code --with-skia}); it renders Java2D through Skia's Graphite — on Windows
 * over Dawn and Direct3D 11 by default, over Vulkan as the alternative — into
 * its own window surfaces. The backend is Windows-only.
 * <p>
 * The switches, all under {@code sun.java2d.skia}:
 * <ul>
 * <li>{@code sun.java2d.skia=true|false}: the backend on or off (default off).</li>
 * <li>{@code sun.java2d.skia.verbose=true}: what was chosen and why, on stderr
 *     (as {@code sun.java2d.vulkan=True} and {@code sun.java2d.d3d=True} report).</li>
 * <li>{@code sun.java2d.skia.pipelineCache=<directory>|off}: where Graphite's
 *     compiled pipelines persist between runs (default Windows' per-user cache
 *     location, keyed by the Skia release; see {@link SKPipelineCache}).</li>
 * <li>{@code sun.java2d.skia.shapes=analytic|marlin}: antialiased shapes drawn
 *     by Skia's analytic rasterizer with Marlin's coverage masks for general
 *     paths (default), or every shape through Marlin's masks as the other
 *     pipelines do (the fallback for a rendering difference).</li>
 * <li>{@code sun.java2d.skia.graphite.api=dawn|vulkan} (Windows): the graphics
 *     API Graphite tries first; Dawn over Direct3D 11 by default, the other one
 *     next (see {@link SKGraphicsAPIFallback}).</li>
 * <li>{@code sun.java2d.skia.gpuBlocklist=<file>|ignore}: a GPU list replacing
 *     the shipped one ({@link SKGPUList}), or none; {@code verbose} names the
 *     entry that kept an API from being used.</li>
 * <li>{@code sun.java2d.skia.imageAtlas=false}: images small enough for the
 *     image atlas are drawn as single textures instead.</li>
 * <li>{@code sun.java2d.skia.text.lcdCompositing=blend|twoPass|accumulate}: how
 *     LCD text is composited, for the backend's tests and for triage; the default
 *     is the GPU's (see {@link Configuration}).</li>
 * <li>{@code sun.java2d.skia.dawn.api=d3d12|d3d11}: the Direct3D version Dawn
 *     tries first (Direct3D 11 by default), for the backend's tests and for triage
 *     (read by Win32SKGraphicsAPIFallback.cpp).</li>
 * </ul>
 * <p>
 * The backend starts on the first call of {@link #isSkiaEnabled} or {@link #runningOn},
 * as the Vulkan and Direct3D pipelines start on their first query ({@code VKEnv},
 * {@code D3DGraphicsDevice}): the graphics environment makes that call while it
 * creates its screen devices ({@code Win32GraphicsEnvironment.makeScreenDevice}),
 * so that the start happens once, early, and before any surface is created. Code
 * that must not start it (the toolkit's sync and shutdown) asks {@link #isSkiaStarted}.
 */
public final class SKEnvironment {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.skia.SKEnvironment");

    private static final boolean REQUESTED = "true".equalsIgnoreCase(System.getProperty("sun.java2d.skia"));
    private static final boolean VERBOSE = Boolean.getBoolean("sun.java2d.skia.verbose");

    /** {@code analytic} (the default) or {@code marlin}: how antialiased shapes are rasterized. */
    private static final String SHAPES = shapesProperty();
    /** {@code dawn}, {@code vulkan} or {@code null}: the graphics API Graphite tries first, if one was asked for. */
    private static final String GRAPHITE_API = graphiteAPIProperty();
    private static final boolean IMAGE_ATLAS = !"false".equalsIgnoreCase(System.getProperty("sun.java2d.skia.imageAtlas"));

    /** The side of the backend's glyph cache textures in texels. */
    private static final int GLYPH_CACHE_TEXELS = 1024;

    private static String shapesProperty() {
        String shapes = System.getProperty("sun.java2d.skia.shapes", "analytic").toLowerCase(Locale.ROOT);
        if (!shapes.equals("analytic") && !shapes.equals("marlin")) {
            if (VERBOSE) {
                System.err.println("sun.java2d.skia.shapes=" + shapes + " is not analytic or marlin; using analytic");
            }
            return "analytic";
        }
        return shapes;
    }

    private static String graphiteAPIProperty() {
        String requested = System.getProperty("sun.java2d.skia.graphite.api");
        if (requested == null) {
            return null;
        }
        String api = requested.toLowerCase(Locale.ROOT);
        if (!api.equals("dawn") && !api.equals("vulkan")) {
            if (VERBOSE) {
                System.err.println("sun.java2d.skia.graphite.api=" + requested
                                   + " is not dawn or vulkan; using the platform's default");
            }
            return null;
        }
        return api;
    }

    /**
     * How LCD text is composited ({@code sun.java2d.skia.text.lcdCompositing}); the ordinals
     * are what the native side receives ({@code SKLCDCompositing}, same order).
     */
    enum LCDCompositing {
        /** A blender that reads the destination: exact, where the shader can read the framebuffer. */
        BLEND("blend"),
        /** Two passes without a destination read, as DirectWrite blends ClearType. */
        TWO_PASS("twoPass"),
        /** The accumulated coverage of a batch, drawn once: for triage. */
        ACCUMULATE("accumulate");

        private final String propertyValue;

        LCDCompositing(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        /** The value as the property spells it: {@code blend}, {@code twoPass}, {@code accumulate}. */
        String propertyValue() {
            return propertyValue;
        }

        /** The mode a property value names, case-insensitively, or {@code null} if it names none. */
        static LCDCompositing parse(String value) {
            for (LCDCompositing compositing : values()) {
                if (compositing.propertyValue.equalsIgnoreCase(value)) {
                    return compositing;
                }
            }
            return null;
        }
    }

    /**
     * The native backend's configuration by GPU architecture: a blender that reads the
     * destination composites LCD text exactly where the shader can read the framebuffer;
     * elsewhere two passes without a destination read; the accumulated coverage of a
     * batch only on request ({@code sun.java2d.skia.text.lcdCompositing}).
     */
    private record Configuration(LCDCompositing lcdCompositing, boolean framebufferFetch) {
        static Configuration fromProperties(boolean framebufferFetch) {
            LCDCompositing lcdCompositing = framebufferFetch ? LCDCompositing.BLEND : LCDCompositing.TWO_PASS;
            String requested = System.getProperty("sun.java2d.skia.text.lcdCompositing");
            if (requested != null) {
                LCDCompositing parsed = LCDCompositing.parse(requested);
                if (parsed != null) {
                    lcdCompositing = parsed;
                } else if (VERBOSE) {
                    System.err.println("sun.java2d.skia.text.lcdCompositing=" + requested
                                       + " is not blend, twoPass or accumulate; using " + lcdCompositing.propertyValue());
                }
            }
            return new Configuration(lcdCompositing, framebufferFetch);
        }
    }

    /**
     * Set once {@link #startOnce} ran, whether or not the backend started: a start is not
     * retried. Written after {@link #runningOn}, so that a reader who sees it sees the outcome.
     */
    private static volatile boolean startAttempted;

    /**
     * What the backend renders on, once it started: the Skia release, the graphics API and
     * the adapter, as the platform describes them ({@code "Skia m156 Graphite/Dawn on NVIDIA
     * GeForce ... (D3D11)"}); {@code null} before the start and when it did not start.
     */
    private static volatile String runningOn;

    private static Configuration configuration;

    private SKEnvironment() {}

    /**
     * Whether the Skia backend renders; starts it on the first call (loads libawt_skia and
     * creates the GPU device), which Win32GraphicsEnvironment.makeScreenDevice is meant to make.
     */
    public static boolean isSkiaEnabled() {
        return runningOn() != null;
    }

    /**
     * Whether the Skia backend has been started and renders, without starting it: for the
     * toolkit's sync and shutdown, which must not create a GPU device in a process that never
     * created a screen device.
     */
    public static boolean isSkiaStarted() {
        return runningOn != null;
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

    /**
     * Whether the shapes Skia renders analytically are rasterized by Skia, Marlin's masks
     * drawing the rest ({@code analytic}), instead of Marlin's masks for every shape.
     */
    public static boolean rasterizesShapes() {
        return !SHAPES.equals("marlin");
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
     * Loads libawt_skia, hands it the configuration and the pipeline cache, and starts Graphite
     * on the first usable graphics API.
     *
     * @return what the backend runs on ({@link #runningOn}), or {@code null} if it did not start
     */
    private static String start() {
        String startedOn = null;
        try {
            if (loadLibrary()) {
                // before the start: an API creates its device as it starts, and the device takes
                // the pipeline cache and the configuration then
                configuration = Configuration.fromProperties(framebufferFetchNative());
                configureNative(configuration.lcdCompositing().ordinal(), configuration.framebufferFetch(),
                                GLYPH_CACHE_TEXELS, IMAGE_ATLAS);
                SKPipelineCache.configure();
                startedOn = SKGraphicsAPIFallback.startFirstUsableAPI(SKGPUList.load(), GRAPHITE_API);
                if (startedOn != null) {
                    SKPipelineCache.addShutdownHook();
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            // called while the GraphicsEnvironment is constructed: nothing from here may keep
            // it from starting; the platform's default pipeline renders instead
            warn("Skia backend is not available: " + failure);
            startedOn = null;
        }
        if (VERBOSE || log.isLoggable(PlatformLogger.Level.FINE)) {
            report("Skia rendering enabled: " + (startedOn != null
                    ? "YES (" + startedOn + ", shapes: " + SHAPES
                      + ", lcdCompositing: " + configuration.lcdCompositing().propertyValue()
                      + ", imageAtlas: " + (IMAGE_ATLAS ? "on" : "off")
                      + ", pipelineCache: " + (SKPipelineCache.directory() != null ? SKPipelineCache.directory() : "off") + ")"
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

    /** Whether the GPU reads the framebuffer in the shader, so that a destination read costs nothing. */
    private static native boolean framebufferFetchNative();

    /** Hands the architecture-dependent configuration to the native backend before any context exists. */
    private static native void configureNative(int lcdCompositing, boolean framebufferFetch, int glyphCacheTexels,
                                               boolean imageAtlas);

    // ---- diagnostics, for the backend's tests (test/jdk/jb/java/awt/Graphics2D) ----------------------

    /** How LCD text is composited, as {@code sun.java2d.skia.text.lcdCompositing} spells it; empty if Skia is off. */
    @SuppressWarnings("unused")
    public static String lcdCompositing() {
        return isSkiaEnabled() ? configuration.lcdCompositing().propertyValue() : "";
    }

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

    /** The image atlas's counters (SKImageAtlas.h). */
    @SuppressWarnings("unused")
    public static long[] imageAtlasCounters() {
        return isSkiaEnabled() ? imageAtlasCountersNative() : new long[0];
    }

    private static native long[] imageAtlasCountersNative();
}
