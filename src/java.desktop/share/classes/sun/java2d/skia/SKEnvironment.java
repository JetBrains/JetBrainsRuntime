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

import sun.font.FontUtilities;

import java.util.Locale;

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
     * Who rasterizes antialiased shapes: {@code analytic} (the default: Skia
     * for the shapes it renders analytically, that is rectangles, round
     * rectangles and ellipses under any transform, Marlin coverage tiles for
     * the rest),
     * {@code marlin} (coverage tiles for everything) or {@code skia}
     * (everything through Skia, general paths via Graphite's path atlas).
     * Anything else counts as {@code marlin}.
     */
    private static final String SHAPES = shapesProperty();
    /**
     * Who strokes antialiased shapes that Skia rasterizes: {@code skia} (the
     * default: Skia's stroker, analytic for ovals and round rectangles) or
     * {@code java} (Java's stroker produces the outline, as for Marlin, and
     * Skia fills it; dashed strokes always take this route). The two differ
     * at joins and along curves by the two strokers' geometry, by up to 50
     * levels on one-pixel strokes; anything else counts as {@code skia}.
     */
    private static final boolean STROKES_BY_JAVA = strokesProperty();
    /**
     * {@code sun.java2d.skia.text=skia}: text drawn through Skia's own text
     * path (Skia's strike, glyph atlas and blending) from the glyph codes and
     * positions Java laid out, instead of Java2D's glyph images; needs the Skia
     * font scaler. Default {@code glyphs}: the glyph images through the
     * backend's atlas.
     */
    private static final String TEXT = System.getProperty("sun.java2d.skia.text", "glyphs").toLowerCase(Locale.ROOT);
    private static final boolean TEXT_BY_SKIA = TEXT.equals("skia") || TEXT.equals("shaper") || TEXT.equals("paragraph");
    /**
     * {@code sun.java2d.skia.text=shaper}: in addition, {@code drawString} and
     * {@code drawChars} hand the characters to Skia's shaper (HarfBuzz with
     * Skia's Unicode module, Skia's font fallback) and draw the resulting text
     * blob, instead of Java's layout ({@code GlyphList}, {@code TextLayout}).
     * Glyph vectors still carry Java's layout.
     */
    private static final boolean TEXT_SHAPED_BY_SKIA = TEXT.equals("shaper") || TEXT.equals("paragraph");
    /**
     * {@code sun.java2d.skia.text=paragraph}: in addition, attributed strings
     * ({@code drawString(AttributedCharacterIterator)}) are laid out by Skia's
     * paragraph engine (SkParagraph: runs of fonts and colours, underline and
     * strikethrough) instead of Java's {@code TextLayout}; attributes the route
     * does not model fall back to it.
     */
    private static final boolean TEXT_PARAGRAPH_BY_SKIA = TEXT.equals("paragraph");
    /**
     * {@code sun.java2d.skia.text.smoothing=true}: Skia's text path asks the
     * platform for smoothed glyph masks (Skia's hinting "normal", which on the
     * CoreText host takes CoreGraphics' font smoothing) as Chrome does; default
     * off: Skia's plain coverage.
     */
    private static final boolean TEXT_SMOOTHING = Boolean.getBoolean("sun.java2d.skia.text.smoothing");
    /**
     * {@code sun.java2d.skia.strikeCache=<MB>}: the budget of Skia's strike cache
     * (the CPU-side glyph masks of Skia's text path, {@code SkGraphics::SetFontCacheLimit}).
     * Default 64 MB: Skia keys a strike by the quantized paint colour as well as by the
     * font, and the cg route adds one per text luminance class, so Skia's own 2 MB
     * default re-rasterizes every frame once a few dozen colours are on screen
     * (ledger §19b.1); 0 keeps Skia's default.
     */
    private static final int STRIKE_CACHE_MB = Integer.getInteger("sun.java2d.skia.strikeCache", 64);
    /**
     * {@code sun.java2d.skia.glyphAtlas=<MB>}: the budget of Graphite's glyph atlas
     * ({@code ContextOptions::fGlyphCacheTextureMaximumBytes}, the GPU-side glyph
     * masks of Skia's text path); 0 keeps Skia's default of 8 MB.
     */
    private static final int GLYPH_ATLAS_MB = Integer.getInteger("sun.java2d.skia.glyphAtlas", 0);
    /**
     * {@code sun.java2d.skia.glyphCache=<texels>}: the side of the pipe's glyph cache
     * textures (Java's glyph images, {@code SKGlyphCache}; cells of 32 x 32), a multiple
     * of 32 between 32 and 16384. Unset or invalid: 1024, or 2048 when glyphs are
     * rasterized per text luminance class ({@link FontUtilities#textLuminanceDependent},
     * the cg route): one strike per class multiplies the glyph set (ledger §19b.1).
     */
    private static final int GLYPH_CACHE_TEXELS = Integer.getInteger("sun.java2d.skia.glyphCache", 0);

    private static int glyphCacheTexels() {
        if (GLYPH_CACHE_TEXELS >= 32 && GLYPH_CACHE_TEXELS <= 16384 && GLYPH_CACHE_TEXELS % 32 == 0) {
            return GLYPH_CACHE_TEXELS;
        }
        return FontUtilities.textLuminanceDependent ? 2048 : 1024;
    }

    private static boolean strokesProperty() {
        String strokes = System.getProperty("sun.java2d.skia.strokes", "skia");
        if (!strokes.equalsIgnoreCase("skia") && !strokes.equalsIgnoreCase("java")) {
            if (VERBOSE) {
                System.err.println("sun.java2d.skia.strokes=" + strokes + " is not skia or java; using skia");
            }
            return false;
        }
        return strokes.equalsIgnoreCase("java");
    }

    private static String shapesProperty() {
        String shapes = System.getProperty("sun.java2d.skia.shapes", "analytic");
        if (!shapes.equalsIgnoreCase("analytic") && !shapes.equalsIgnoreCase("marlin")
                && !shapes.equalsIgnoreCase("skia")) {
            if (VERBOSE) {
                System.err.println("sun.java2d.skia.shapes=" + shapes + " is not analytic, marlin or skia; using marlin");
            }
            return "marlin";
        }
        return shapes;
    }

    private static volatile boolean initialized;
    private static String description;

    /** How LCD text is composited; the ordinals are what the native side receives. */
    enum LcdMode { BLEND, TWOPASS, ACCUMULATE }

    /**
     * Configuration of the native backend that depends on the GPU architecture
     * rather than on Java2D semantics, with defaults by whether the shader can
     * read the framebuffer and system properties to override them:
     * <ul>
     * <li>{@code sun.java2d.skia.msaa}: Graphite's internal sample count (1, 2,
     *     4, 8). Multisampling quantizes antialiased coverage to that many
     *     levels but makes general paths cheap; non-AA geometry stays
     *     pixel-exact either way.</li>
     * <li>{@code sun.java2d.skia.blitQuads}: {@code mesh} (textured
     *     two-triangle mesh, the default) or {@code edgeAA} (Graphite's
     *     per-edge-AA image draw with no AA edges) for image blits; anything
     *     else counts as {@code mesh}.</li>
     * <li>{@code sun.java2d.skia.lcd}: how LCD (subpixel) text is composited.
     *     {@code blend}: a blender reads the destination and blends each
     *     colour channel in the destination's gamma, as the software loops
     *     do; exact, and cheap only where the GPU reads the framebuffer in the
     *     shader (Metal on Apple GPUs). {@code twopass}: two draws without a
     *     destination read (multiply by the inverse coverage, add the colour
     *     times the coverage, as DirectWrite blends ClearType), with the
     *     gamma correction folded into the coverage (exact on white and black
     *     backgrounds, a few levels off on others), and cheap everywhere.
     *     {@code accumulate}: the same blend, but the glyphs of a batch are
     *     first drawn into a coverage texture (where overlapping glyphs
     *     combine in order, so the batch needs no splitting) and the two draws
     *     composite that texture over the batch's bounds.</li>
     * </ul>
     * {@code sun.java2d.skia.shapes} (see {@link #rasterizesShapes()}) is the
     * fourth knob; it is decided on the Java side.
     */
    private record Configuration(int msaaSamples, boolean edgeAAQuads, LcdMode lcdMode, boolean framebufferFetch) {
        static Configuration defaultsFor(boolean framebufferFetch) {
            // Measured on Metal (Apple M1 Max) and Dawn/D3D11: the path atlas beats
            // 4x MSAA on fidelity and MSAA only wins on general paths; meshes and
            // the edge-AA draw measured equal, the mesh is the exact one. Vulkan
            // inherits these values until measured. A destination read without
            // framebuffer fetch costs a render pass and a copy per draw (Dawn on
            // Direct3D, Metal on Intel and AMD GPUs), which LCD text cannot
            // afford once overlapping glyphs are drawn separately. Of the two
            // modes without a destination read, accumulate is the faster one
            // (one layer per batch, no split) and the closer one at overlaps;
            // in IDE scenes the two are indistinguishable (2026-10-21).
            return new Configuration(1, false, framebufferFetch ? LcdMode.BLEND : LcdMode.ACCUMULATE, framebufferFetch);
        }

        Configuration withProperties() {
            int samples = Integer.getInteger("sun.java2d.skia.msaa", msaaSamples);
            String blits = System.getProperty("sun.java2d.skia.blitQuads");
            boolean edgeAA = blits == null ? edgeAAQuads : "edgeAA".equalsIgnoreCase(blits);
            String lcd = System.getProperty("sun.java2d.skia.lcd");
            LcdMode mode = lcdMode;
            if (lcd != null) {
                try {
                    mode = LcdMode.valueOf(lcd.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    if (VERBOSE) {
                        System.err.println("sun.java2d.skia.lcd=" + lcd + " is not blend, twopass or accumulate; using "
                                + lcdMode.name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return new Configuration(samples, edgeAA, mode, framebufferFetch);
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
    public static boolean isSkiaEnabled() {
        if (!initialized) {
            init();
        }
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

    /** Whether Java's stroker outlines the strokes Skia rasterizes, instead of Skia's. */
    public static boolean strokesByJava() {
        return STROKES_BY_JAVA;
    }

    /** Whether text goes through Skia's own text path (strike, atlas, blending) rather than Java2D's glyph images. */
    public static boolean textBySkia() {
        return TEXT_BY_SKIA;
    }

    /** Whether drawString's characters are shaped by Skia's shaper rather than laid out by Java. */
    public static boolean textShapedBySkia() {
        return TEXT_SHAPED_BY_SKIA;
    }

    /** Whether attributed strings are laid out by Skia's paragraph engine rather than Java's TextLayout. */
    public static boolean textParagraphBySkia() {
        return TEXT_PARAGRAPH_BY_SKIA;
    }

    /** Whether Skia's text path requests the platform's font smoothing for its glyph masks. */
    public static boolean textSmoothing() {
        return TEXT_SMOOTHING;
    }

    /** How many glyphs the backend has drawn through Skia's text path so far (diagnostics, tests). */
    public static long glyphsDrawnBySkia() {
        return isSkiaEnabled() ? glyphsDrawnBySkiaNative() : 0;
    }

    /** How many glyphs Skia's shaper has produced for the backend so far (diagnostics, tests). */
    public static long glyphsShapedBySkia() {
        return isSkiaEnabled() ? glyphsShapedBySkiaNative() : 0;
    }

    /** How many attributed strings Skia's paragraph engine has laid out so far (diagnostics, tests). */
    public static long paragraphsBySkia() {
        return isSkiaEnabled() ? paragraphsBySkiaNative() : 0;
    }

    /**
     * How many times the native backend has finished a flush (a render-queue buffer
     * or a direct entry) and submitted the GPU work so far (diagnostics, tests).
     */
    public static long flushes() {
        return isSkiaEnabled() ? flushesNative() : 0;
    }

    private static Boolean libraryLoaded;

    /**
     * Loads libawt_skia once and tells whether it is there. The font scaler
     * ({@code sun.font.SKFontScaler}) needs only the library, not the GPU
     * backend, so it can serve the software loops and the platform's pipeline.
     */
    @SuppressWarnings("restricted")
    public static synchronized boolean loadLibrary() {
        if (libraryLoaded == null) {
            try {
                System.loadLibrary("awt_skia");
                libraryLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                if (VERBOSE) {
                    System.err.println("Skia library is not available: " + e);
                }
                log.warning("Skia library is not available", e);
                libraryLoaded = false;
            }
        }
        return libraryLoaded;
    }

    /**
     * Human-readable description of the GPU API, device and Skia milestone,
     * or {@code null} if the backend is not enabled.
     */
    public static String getDescription() {
        if (!initialized) {
            init();
        }
        return description;
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            initOnce();
        } finally {
            initialized = true;
        }
    }

    private static void initOnce() {
        if (!REQUESTED) {
            return;
        }
        try {
            if (!loadLibrary()) {
                return;
            }
            description = initNative();
            if (description != null) {
                configuration = Configuration.defaultsFor(framebufferFetchNative()).withProperties();
                configureNative(configuration.msaaSamples(), configuration.edgeAAQuads(),
                                configuration.lcdMode().ordinal(), configuration.framebufferFetch(),
                                GLYPH_ATLAS_MB * 1024L * 1024L, glyphCacheTexels(),
                                FontUtilities.textLuminanceClasses);
                if (STRIKE_CACHE_MB > 0) {
                    setStrikeCacheLimitNative(STRIKE_CACHE_MB * 1024L * 1024L);
                }
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
                    ? "YES (" + description + ", shapes: " + SHAPES + ", strokes: " + (STROKES_BY_JAVA ? "java" : "skia") + ", text: " + (TEXT_PARAGRAPH_BY_SKIA ? "paragraph" : TEXT_SHAPED_BY_SKIA ? "shaper" : TEXT_BY_SKIA ? "skia" : "glyphs")
                      + ", msaa: " + configuration.msaaSamples()
                      + ", blitQuads: " + (configuration.edgeAAQuads() ? "edgeAA" : "mesh")
                      + ", lcd: " + configuration.lcdMode().name().toLowerCase(Locale.ROOT) + ")"
                    : "NO");
            if (VERBOSE) {
                System.err.println(msg);
            }
            log.fine(msg);
        }
    }

    /** Describes the GPU API and device Skia will run on; {@code null} if unavailable. */
    private static native String initNative();

    /** Whether the GPU reads the framebuffer in the shader, so that a destination read costs nothing. */
    private static native boolean framebufferFetchNative();

    /** Hands the architecture-dependent configuration to the native backend before any context exists. */
    private static native void configureNative(int msaaSamples, boolean edgeAAQuads, int lcdMode, boolean framebufferFetch,
                                               long glyphAtlasBytes, int glyphCacheTexels, int luminanceClasses);
    private static native long glyphsDrawnBySkiaNative();

    private static native long glyphsShapedBySkiaNative();

    private static native long paragraphsBySkiaNative();

    private static native long flushesNative();

    private static native void setStrikeCacheLimitNative(long bytes);

    /** The budget of Skia's strike cache in bytes as the backend runs it (diagnostics, tests). */
    public static long strikeCacheLimit() {
        return isSkiaEnabled() ? strikeCacheLimitNative() : 0;
    }

    private static native long strikeCacheLimitNative();

    /** The budget of Graphite's glyph atlas in bytes as the backend creates its contexts (diagnostics, tests). */
    public static long glyphAtlasBudget() {
        return isSkiaEnabled() ? glyphAtlasBudgetNative() : 0;
    }

    private static native long glyphAtlasBudgetNative();

    /** The side of the pipe's glyph cache textures in texels, as the backend creates them (diagnostics, tests). */
    public static int glyphCacheSize() {
        return isSkiaEnabled() ? glyphCacheSizeNative() : 0;
    }

    private static native int glyphCacheSizeNative();

    /** The text luminance classes Skia's text path uses for the cg route, as handed to the backend (tests). */
    public static int luminanceClasses() {
        return isSkiaEnabled() ? luminanceClassesNative() : 0;
    }

    private static native int luminanceClassesNative();
}
