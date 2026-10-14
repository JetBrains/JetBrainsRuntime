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
    /**
     * Who rasterizes antialiased shapes: {@code marlin} (coverage tiles, the
     * default), {@code analytic} (Skia for the shapes it renders analytically:
     * axis-aligned rectangles, round rectangles and ellipses; Marlin for the
     * rest) or {@code skia} (everything).
     */
    private static final String SHAPES =
            System.getProperty("sun.java2d.skia.shapes", "marlin");

    private static boolean initialized;
    private static String description;

    /**
     * Configuration of the native backend that depends on the GPU architecture
     * rather than on Java2D semantics, with defaults per GPU API and system
     * properties to override them:
     * <ul>
     * <li>{@code sun.java2d.skia.msaa}: Graphite's internal sample count (1, 2,
     *     4, 8). Multisampling quantizes antialiased coverage to that many
     *     levels but makes general paths cheap; non-AA geometry stays
     *     pixel-exact either way.</li>
     * <li>{@code sun.java2d.skia.blitQuads}: {@code mesh} (textured
     *     two-triangle mesh) or {@code edgeAA} (Graphite's per-edge-AA image
     *     draw with no AA edges) for image blits.</li>
     * </ul>
     * {@code sun.java2d.skia.shapes} (see {@link #rasterizesShapes()}) is the
     * third knob; it is decided on the Java side.
     */
    private record Configuration(int msaaSamples, boolean edgeAAQuads) {
        static Configuration defaultsFor(String gpuApi) {
            return switch (gpuApi) {
                // Metal on Apple GPUs: the path atlas beats 4x MSAA on fidelity,
                // MSAA only wins on general paths; meshes and
                // the edge-AA draw measured equal, the mesh is the exact one.
                case "metal" -> new Configuration(1, false);
                // Vulkan and Dawn/D3D: not measured yet, Metal's values until then.
                default -> new Configuration(1, false);
            };
        }

        Configuration withProperties() {
            int samples = Integer.getInteger("sun.java2d.skia.msaa", msaaSamples);
            String blits = System.getProperty("sun.java2d.skia.blitQuads");
            boolean edgeAA = blits == null ? edgeAAQuads : "edgeAA".equalsIgnoreCase(blits);
            return new Configuration(samples, edgeAA);
        }
    }

    private static Configuration configuration;

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

    /** Whether every antialiased shape is rasterized by Skia rather than by Marlin. */
    public static boolean rasterizesShapes() {
        return "skia".equalsIgnoreCase(SHAPES);
    }

    /** Whether Skia rasterizes the antialiased shapes it has analytic renderers for. */
    public static boolean rasterizesAnalyticShapes() {
        return "analytic".equalsIgnoreCase(SHAPES);
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
            if (description != null) {
                configuration = Configuration.defaultsFor(gpuApiNative()).withProperties();
                configureNative(configuration.msaaSamples(), configuration.edgeAAQuads());
            }
        } catch (UnsatisfiedLinkError e) {
            if (VERBOSE) {
                System.err.println("Skia backend is not available: " + e);
            }
            log.warning("Skia backend is not available", e);
            description = null;
        }
        if (VERBOSE || log.isLoggable(PlatformLogger.Level.FINE)) {
            String msg = "Skia rendering enabled: " + (description != null
                    ? "YES (" + description + ", shapes: " + SHAPES + ", msaa: " + configuration.msaaSamples()
                      + ", blitQuads: " + (configuration.edgeAAQuads() ? "edgeAA" : "mesh") + ")"
                    : "NO");
            if (VERBOSE) {
                System.err.println(msg);
            }
            log.fine(msg);
        }
    }

    /** Describes the GPU API and device Skia will run on; {@code null} if unavailable. */
    private static native String initNative();

    /** The GPU API Graphite runs on here: {@code metal}, {@code vulkan} or {@code dawn}. */
    private static native String gpuApiNative();

    /** Hands the architecture-dependent configuration to the native backend before any context exists. */
    private static native void configureNative(int msaaSamples, boolean edgeAAQuads);
}
