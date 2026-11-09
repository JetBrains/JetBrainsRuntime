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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.java2d.SurfaceData;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKPipelineCache;

/**
 * The shared part of the Skia backend's tests: whether the run line's pipeline and
 * Graphite API are there to test, rendering a painter on a GPU pipeline and with the
 * software loops and comparing the two within a measured tolerance, rendering into
 * volatile images that may lose their contents, the backend's counters by name, and
 * child JVMs that run with the backend. A test that uses it names
 * {@code java.desktop/sun.java2d} and {@code java.desktop/sun.java2d.skia} in its
 * {@code @modules} and {@code /test/lib} (jtreg.SkippedException: a test with nothing to
 * test counts as skipped) in its {@code @library}.
 */
public final class PipelineComparison {

    /** How often a test renders into volatile images that keep losing their contents before it fails. */
    public static final int ATTEMPTS = 5;

    /** The side of the volatile image that probes which pipeline owns a configuration's surfaces. */
    private static final int PROBE_SIZE = 16;

    /** The package every Skia surface, configuration and graphics class is in. */
    public static final String SKIA_PACKAGE = ".skia.";

    /** A pixel of the reference counts as inked where its blue channel is below this (dark ink on a white background). */
    private static final int INK_BELOW_BLUE = 128;

    /** A pixel of the rendering counts as background where its blue channel is this (white). */
    private static final int BACKGROUND_BLUE = 0xff;

    /** One character of the difference map stands for this many pixels across and down. */
    private static final int MAP_CELL_WIDTH = 4;
    private static final int MAP_CELL_HEIGHT = 8;

    /** The side of {@link #fourColorTile}, two colors across and two down. */
    public static final int TILE_SIZE = 6;

    /**
     * Indices into {@link SKEnvironment#glyphCacheCounters}, in the order of SKGlyphCache.h's
     * {@code SKGlyphCacheCounter}.
     */
    public static final class GlyphCacheCounter {
        /** Glyphs drawn from a cell of the cache. */
        public static final int GLYPHS_CACHED = 0;
        /** Cells staged: the first draw of a glyph image. */
        public static final int CELLS_STAGED = 1;
        /** Cells evicted: a staged glyph replaced a cached one. */
        public static final int CELLS_EVICTED = 2;
        /** Atlas row uploads. */
        public static final int ATLAS_UPLOADS = 3;
        /** Glyphs drawn uncached by the backend: no cell fit or was free. */
        public static final int GLYPHS_UNCACHED = 4;
        /** Batch draws: a batch holding quads drawn (two passes count once). */
        public static final int BATCH_DRAWS = 5;

        private GlyphCacheCounter() {
        }
    }

    /**
     * Indices into {@link SKEnvironment#imageAtlasCounters}, in the order of SKImageAtlas.h's
     * {@code SKImageAtlasCounter}.
     */
    public static final class ImageAtlasCounter {
        /** Blits drawn from the atlas. */
        public static final int BLITS = 0;
        /** Copies of images into the atlas. */
        public static final int COPIES = 1;
        /** Refills of the atlas. */
        public static final int REFILLS = 2;
        /** The atlas batch's mesh draws. */
        public static final int BATCH_DRAWS = 3;

        private ImageAtlasCounter() {
        }
    }

    /**
     * Indices into {@link SKPipelineCache#counters}, in the order of SKPipelineCache.cpp's
     * {@code SKPipelineCacheCounters}.
     */
    public static final class PipelineCacheCounter {
        /** Lookups. */
        public static final int LOADS = 0;
        /** Lookups that found data. */
        public static final int HITS = 1;
        /** Blobs handed over for storing. */
        public static final int STORES = 2;

        private PipelineCacheCounter() {
        }
    }

    /**
     * The release traces of the backend's assertions (J2D_TRACE_ERROR): a surface wrapped while it
     * is another context's (SKSurfaceState::wrap), and texture paint images of a disposed context
     * that something other than the context's own caches still references when it goes
     * (SKGraphicsConfig's destructor, from the images' references; the backend tracks the images only
     * while this trace level is on). Neither can be provoked from Java.
     */
    public static final String WRAPPED_BY_ANOTHER_CONTEXT_TRACE = "SKSurfaceState::wrap: the surface is one of another context's surfaces";
    public static final String IMAGES_OUTLIVE_CONTEXT_TRACE = "texture paint images of the disposed context are held outside it";

    /** The system property that tells a child of {@link #ranInChild} that it is the child. */
    private static final String CHILD_PROPERTY = "skia.test.child";

    /**
     * How long a child of {@link #ranInChild} may run, before the timeout factor: less than jtreg's
     * default of 120 s for the parent, so that the parent kills a hung child and reports it.
     */
    private static final long CHILD_TIMEOUT_S = 90;

    /** J2D_TRACE_ERROR: the release traces of errors, the assertions' among them. */
    private static final String TRACE_LEVEL_ERROR = "1";

    /**
     * The prefixes of the options that start a debugger agent (JDWP): the parent's, given to the
     * child, would have it bind the parent's port (and fail) or wait for a debugger until it is killed.
     */
    private static final List<String> DEBUG_AGENT_OPTION_PREFIXES = List.of("-agentlib:jdwp", "-Xrunjdwp", "-Xdebug");

    private PipelineComparison() {
    }

    /**
     * Runs the test again in a child JVM, with the options of this one (its run line's, jtreg's
     * test.vm.opts and test.java.opts among them, so they are not added again; a debugger agent's
     * not, {@link #DEBUG_AGENT_OPTION_PREFIXES}) and J2D_TRACE_LEVEL set
     * to errors, so that the backend's release traces can be checked: the child must exit with 0 within
     * {@link #CHILD_TIMEOUT_S} (else it is killed with its descendants, its window with it, and the test
     * fails with what it printed) and print none of the forbidden traces; a skip in the child skips. Call
     * it first in main.
     *
     * @return true in the parent, once the child passed (main returns then); false in the child, which
     *         runs the test
     */
    public static boolean ranInChild(Class<?> mainClass, String[] args, String... forbiddenTraces) throws Exception {
        if (Boolean.getBoolean(CHILD_PROPERTY)) {
            return false;
        }
        List<String> command = new ArrayList<>();
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (DEBUG_AGENT_OPTION_PREFIXES.stream().noneMatch(option::startsWith)) {
                command.add(option);
            }
        }
        command.add("-D" + CHILD_PROPERTY + "=true");
        command.add(mainClass.getName());
        command.addAll(List.of(args));
        ProcessBuilder builder = ProcessTools.createLimitedTestJavaProcessBuilder(command);
        builder.environment().put("J2D_TRACE_LEVEL", TRACE_LEVEL_ERROR);
        Process child = builder.start();
        OutputAnalyzer output = new OutputAnalyzer(child);
        long timeoutSeconds = Utils.adjustTimeout(CHILD_TIMEOUT_S);
        boolean exited = child.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            child.waitFor();
        }
        System.out.println("child stdout:\n" + output.getStdout());
        System.out.println("child stderr:\n" + output.getStderr());
        if (!exited) {
            throw new RuntimeException("the child did not exit within " + timeoutSeconds + " s and was killed");
        }
        String skipped = output.firstMatch(SkippedException.class.getName() + ": (.*)", 1);
        if (skipped != null) {
            throw new SkippedException(skipped);
        }
        output.shouldHaveExitValue(0);
        for (String trace : forbiddenTraces) {
            output.shouldNotContain(trace);
        }
        return true;
    }

    /** The default screen device's default configuration: Skia's, when the backend started. */
    public static GraphicsConfiguration defaultConfiguration() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    }

    /**
     * Skips the test (jtreg's SkippedException) unless the pipeline named on the run line
     * ({@code skia}) owns the configuration's volatile surfaces, on the Graphite API the run
     * line asks for ({@link #requireRequestedAPI}).
     */
    public static void requirePipeline(String pipeline, GraphicsConfiguration graphicsConfig) {
        if (!pipeline.equals("skia")) {
            throw new IllegalArgumentException("unknown pipeline " + pipeline);
        }
        VolatileImage probe = graphicsConfig.createCompatibleVolatileImage(PROBE_SIZE, PROBE_SIZE, Transparency.OPAQUE);
        probe.validate(graphicsConfig);
        String surface = surfaceClassName(probe);
        System.out.println("pipeline " + pipeline + ", surface " + surface);
        if (!surface.contains(SKIA_PACKAGE)) {
            throw new SkippedException("pipeline " + pipeline + ", surface " + surface + ": nothing to test");
        }
        requireRequestedAPI();
    }

    /**
     * Skips the test unless Skia runs on the Graphite API the run line asks for, if it asks
     * for one (sun.java2d.skia.graphite.api: {@code vulkan} for Vulkan, any other value for
     * Dawn, the default; sun.java2d.skia.dawn.api: Dawn on that Direct3D version); see
     * {@link #requireAPI}. Without either property whatever API the backend started on is fine.
     */
    public static void requireRequestedAPI() {
        requireSkia();
        String graphiteAPI = System.getProperty("sun.java2d.skia.graphite.api");
        String dawnAPI = System.getProperty("sun.java2d.skia.dawn.api");
        if (graphiteAPI == null && dawnAPI == null) {
            System.out.println("on " + SKEnvironment.runningOn());
            return;
        }
        requireAPI("vulkan".equalsIgnoreCase(graphiteAPI) ? "Vulkan" : "Dawn",
                   dawnAPI != null ? dawnAPI.toUpperCase(Locale.ROOT) : null, null);
    }

    /** Skips the test unless Skia is enabled (the flag, a runtime with Skia, a device that started). */
    public static void requireSkia() {
        if (SKEnvironment.runningOn() == null) {
            throw new SkippedException("Skia is not enabled: nothing to test");
        }
    }

    /**
     * Skips the test unless the runtime under test has libawt_skia, for tests that must not
     * skip when the backend fails to start on a runtime that has it.
     */
    public static void requireSkiaLibrary() {
        Path library = Path.of(System.getProperty("java.home"), "bin", System.mapLibraryName("awt_skia"));
        if (!Files.exists(library)) {
            throw new SkippedException("no " + library + ": a runtime built without Skia, nothing to test");
        }
    }

    /** Whether Skia runs on the Graphite API ({@code Dawn}, {@code Vulkan}), as {@link SKEnvironment#runningOn} names it. */
    public static boolean runsOn(String api) {
        String runningOn = SKEnvironment.runningOn();
        return runningOn != null && runningOn.contains(" Graphite/" + api + " on ");
    }

    /**
     * Skips the test unless Skia runs on the Graphite API ({@code Dawn}, {@code Vulkan}) and, on
     * Dawn, on the Direct3D version ({@code D3D11}, {@code D3D12}, null: either). The backend
     * runs on the API sun.java2d.skia.graphite.api names or does not start ("Skia is not enabled:
     * nothing to test"); Dawn goes on to its other Direct3D version when the requested one has no
     * adapter ("Dawn on &lt;version&gt; unavailable here: nothing to test").
     *
     * @param startVerbose what sun.java2d.skia.verbose printed while Skia started
     *                     ({@link #startCapturingVerbose}), or null; given, Skia on another API
     *                     than the one asked for that the start did not report fails
     */
    public static void requireAPI(String api, String dawnAPI, String startVerbose) {
        requireSkia();
        String runningOn = SKEnvironment.runningOn();
        if (!runsOn(api)) {
            if (startVerbose != null && !startVerbose.contains("Skia on " + api)) {
                throw new RuntimeException("Graphite/" + api + " was asked for, the start did not report why it was not used: "
                        + runningOn + "\n" + startVerbose);
            }
            throw new SkippedException(api + " unavailable here (" + runningOn + "): nothing to test");
        }
        if (api.equals("Dawn") && dawnAPI != null && !runningOn.endsWith("(" + dawnAPI + ")")) {
            throw new SkippedException("Dawn on " + dawnAPI + " unavailable here (" + runningOn + "): nothing to test");
        }
        System.out.println("on " + runningOn);
    }

    /**
     * Starts Skia ({@link SKEnvironment#isSkiaEnabled}) and returns what sun.java2d.skia.verbose
     * printed on stderr meanwhile (also printed on stdout); call it before anything else starts
     * the backend (the graphics environment does).
     */
    public static String startCapturingVerbose() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            SKEnvironment.isSkiaEnabled();
        } finally {
            System.setErr(originalErr);
        }
        String verbose = captured.toString();
        System.out.print(verbose);
        return verbose;
    }

    /** The class name of the image's primary surface data. */
    public static String surfaceClassName(VolatileImage image) {
        return SurfaceData.getPrimarySurfaceData(image).getClass().getName();
    }

    /** The glyph cache's batch draws so far ({@link GlyphCacheCounter#BATCH_DRAWS}); fails if the backend counts none. */
    public static long glyphBatchDraws() {
        long[] counters = SKEnvironment.glyphCacheCounters();
        if (counters.length <= GlyphCacheCounter.BATCH_DRAWS) {
            throw new RuntimeException("the backend counts no glyph batch draws (" + counters.length + " counters)");
        }
        return counters[GlyphCacheCounter.BATCH_DRAWS];
    }

    /** What each counter advanced by between two readings. */
    public static long[] countersDelta(long[] before, long[] after) {
        long[] delta = new long[before.length];
        for (int counter = 0; counter < before.length; counter++) {
            delta[counter] = after[counter] - before[counter];
        }
        return delta;
    }

    /**
     * The command of a child JVM that runs a test class with the Skia backend requested and its
     * package exported to the test: the options given (the pipeline cache, the Graphite API, ...),
     * then the class and its arguments; for jdk.test.lib.process.ProcessTools, which adds the
     * launcher and the class path.
     */
    public static List<String> skiaChildCommand(List<String> options, Class<?> mainClass, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.desktop/sun.java2d.skia=ALL-UNNAMED",
                "-Dsun.java2d.skia=true"));
        command.addAll(options);
        command.add(mainClass.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    /** Deletes the directory with everything in it, for the scratch directories of child JVMs. */
    public static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> entry.toFile().delete());
        }
    }

    /** One attempt at rendering into volatile images. */
    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws Exception;
    }

    /**
     * Validates the images and runs the attempt until none of them lost its contents meanwhile,
     * at most {@link #ATTEMPTS} times: a device that keeps losing them fails the test instead of
     * hanging it.
     */
    public static <T> T keepingContents(GraphicsConfiguration graphicsConfig, Attempt<T> attempt, VolatileImage... images)
            throws Exception {
        for (int attemptNumber = 1; attemptNumber <= ATTEMPTS; attemptNumber++) {
            for (VolatileImage image : images) {
                image.validate(graphicsConfig);
            }
            T result = attempt.run();
            boolean lost = false;
            for (VolatileImage image : images) {
                lost |= image.contentsLost();
            }
            if (!lost) {
                return result;
            }
            System.out.println("attempt " + attemptNumber + ": the volatile images lost their contents");
        }
        throw new RuntimeException("the volatile images lost their contents " + ATTEMPTS + " times in a row");
    }

    /** The painter's output on the software loops. */
    public static BufferedImage softwareReference(int width, int height, Consumer<Graphics2D> painter) {
        BufferedImage reference = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = reference.createGraphics();
        try {
            painter.accept(graphics);
        } finally {
            graphics.dispose();
        }
        return reference;
    }

    /** The painter's output on a new opaque volatile image of the configuration. */
    public static BufferedImage rendered(GraphicsConfiguration graphicsConfig, int width, int height,
                                         Consumer<Graphics2D> painter) {
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(width, height, Transparency.OPAQUE);
        image.validate(graphicsConfig);
        return rendered(image, painter);
    }

    /** The painter's output on the given volatile image, for tests that paint several frames into one image. */
    public static BufferedImage rendered(VolatileImage image, Consumer<Graphics2D> painter) {
        Graphics2D graphics = image.createGraphics();
        try {
            painter.accept(graphics);
        } finally {
            graphics.dispose();
        }
        BufferedImage snapshot = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        return snapshot;
    }

    /** The largest difference of the two pixels' red, green and blue channels. */
    public static int maxChannelDifference(int referencePixel, int actualPixel) {
        int red = Math.abs(((referencePixel >> 16) & 0xff) - ((actualPixel >> 16) & 0xff));
        int green = Math.abs(((referencePixel >> 8) & 0xff) - ((actualPixel >> 8) & 0xff));
        int blue = Math.abs((referencePixel & 0xff) - (actualPixel & 0xff));
        return Math.max(red, Math.max(green, blue));
    }

    /**
     * @param beyond     pixels whose largest channel difference exceeds the tolerance
     * @param max        the largest channel difference of the region
     * @param missingInk pixels inked in the reference (blue below {@link #INK_BELOW_BLUE}) that are
     *                   background in the rendering (blue at {@link #BACKGROUND_BLUE})
     */
    public record Difference(int beyond, int max, int missingInk) {
    }

    public static Difference compare(BufferedImage reference, BufferedImage actual, int tolerance) {
        return compare(reference, actual, new Rectangle(reference.getWidth(), reference.getHeight()), tolerance);
    }

    public static Difference compare(BufferedImage reference, BufferedImage actual, Rectangle region, int tolerance) {
        int beyond = 0;
        int max = 0;
        int missingInk = 0;
        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                int referencePixel = reference.getRGB(x, y);
                int actualPixel = actual.getRGB(x, y);
                int difference = maxChannelDifference(referencePixel, actualPixel);
                max = Math.max(max, difference);
                if (difference > tolerance) {
                    beyond++;
                }
                if ((referencePixel & 0xff) < INK_BELOW_BLUE && (actualPixel & 0xff) == BACKGROUND_BLUE) {
                    missingInk++;
                }
            }
        }
        return new Difference(beyond, max, missingInk);
    }

    /** Prints where the images differ beyond the tolerance, one character per 4x8 pixels ('#': differs). */
    public static void printDifferenceMap(BufferedImage reference, BufferedImage actual, int tolerance) {
        for (int y = 0; y < reference.getHeight(); y += MAP_CELL_HEIGHT) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < reference.getWidth(); x += MAP_CELL_WIDTH) {
                row.append(maxChannelDifference(reference.getRGB(x, y), actual.getRGB(x, y)) > tolerance ? '#' : '.');
            }
            System.out.println(row);
        }
    }

    /** A {@link #TILE_SIZE} square of four colors of the given alpha, so a TexturePaint fill shows the tile's content and its placement. */
    public static BufferedImage fourColorTile(int imageType, int alpha) {
        BufferedImage tile = new BufferedImage(TILE_SIZE, TILE_SIZE, imageType);
        Color[] colors = { new Color(220, 40, 40, alpha), new Color(40, 180, 60, alpha),
                           new Color(40, 80, 220, alpha), new Color(240, 200, 40, alpha) };
        int half = TILE_SIZE / 2;
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                tile.setRGB(x, y, colors[(x / half) + 2 * (y / half)].getRGB());
            }
        }
        return tile;
    }
}
