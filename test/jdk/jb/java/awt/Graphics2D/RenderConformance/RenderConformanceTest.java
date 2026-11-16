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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;

import jtreg.SkippedException;
import sun.java2d.SurfaceData;

/*
 * @test id=software
 * @summary Render conformance on the software loops: every scene matches its golden exactly but for the loops' own
 *          nondeterminism (the goldens are the loops' output, so this pins the loops; regenerate after a reviewed
 *          change, see below).
 * @key headful
 * @library /test/lib
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 RenderConformanceTest software
 */

/*
 * @test id=skia
 * @summary Render conformance on the Skia backend, each scene within its tolerance of the golden.
 * @key headful
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true RenderConformanceTest skia
 */

/*
 * @test id=d3d
 * @summary Render conformance on the Direct3D pipeline, each scene within its tolerance of the golden, except for
 *          D3D's known deviations (RenderConformanceScenes.knownDeviation), which are reported only.
 * @key headful
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.d3d=true RenderConformanceTest d3d
 */

/*
 * Render conformance: about 1000 scenes of Java2D rendering (RenderConformanceScenes), drawn on one backend and compared
 * scene by scene with goldens, the software loops' output, kept in {@code golden/} next to this file: a sheet
 * per group ({@code <sheet>.png}) and its index of scene names ({@code <sheet>.txt}). Text sheets are per
 * platform ({@code golden/<os>-<arch>/}); a platform without them skips the text scenes. The translucent sheets
 * draw on translucent surfaces (an IntArgbPre image, a translucent volatile image), read back as IntArgb as
 * VolatileImage.getSnapshot does; pixels are compared premultiplied, alpha included.
 *
 * Each run writes {@code renderconformance-<backend>.tsv} (per scene: the largest deviation off and on edges,
 * the pixels over their allowance) and, on failure, {@code renderconformance-<backend>-failures.png} (golden,
 * actual and four times the difference per failing scene) to the working directory. A scene with a known
 * deviation (a shortcut of the software loops or a defect of that backend, RenderConformanceScenes.knownDeviation)
 * is reported, not failed.
 *
 * Regenerating the goldens: {@code -Drenderconformance.generate=<test source dir>/golden} on the software run
 * writes the sheets there; review the change with the failures image of the run before.
 */
// a pixel is on an edge where its 3x3 neighborhood in the golden spans more than this per channel
private static final int EDGE_CONTRAST = 24;
private static final int MAX_FAILURES_SHOWN = 48;
private static final int DIFFERENCE_GAIN = 4;
private static final int MAX_ATTEMPTS = 5;
// the report's histogram: pixels deviating by more than each of these levels
private static final int[] REPORT_LEVELS = {1, 2, 4, 8, 16, 32, 64};

private record Result(RenderConformanceScenes.Scene scene, RenderConformanceScenes.Tolerance tolerance, String knownDeviation,
                      int interiorMax, int edgeMax, int over, int neighborSampled, int[] interiorOver, int[] edgeOver,
                      BufferedImage golden, BufferedImage actual) {
    boolean passed() {
        return over <= tolerance.budget();
    }

    boolean failed() {
        return !passed() && knownDeviation == null;
    }
}

void main(String[] args) throws Exception {
    String backend = args[0];
    String generate = System.getProperty("renderconformance.generate");
    if (generate != null && !backend.equals("software")) {
        throw new IllegalArgumentException("goldens are generated from the software loops only");
    }
    Font textFont = RenderConformanceScenes.bundledFont("JetBrainsMono-Regular.ttf", 13f);
    Font boldTextFont = RenderConformanceScenes.bundledFont("JetBrainsMono-Bold.ttf", 11f);
    Font colorFont = systemColorFont();
    Function<Image, RenderConformanceScenes> renderers =
            volatileSource -> new RenderConformanceScenes(textFont, boldTextFont, colorFont, volatileSource);
    Map<RenderConformanceScenes.Sheet, List<RenderConformanceScenes.Scene>> sheets = new LinkedHashMap<>();
    for (RenderConformanceScenes.Scene scene : RenderConformanceScenes.scenes()) {
        if (backend.equals("d3d") && (scene.composite() == RenderConformanceScenes.Composite.XOR
                                      || scene.composite() == RenderConformanceScenes.Composite.CUSTOM)) {
            continue;   // D3D loses the image's contents on the draws it reads the surface back for (restored image)
        }
        sheets.computeIfAbsent(scene.sheet(), _ -> new ArrayList<>()).add(scene);
    }
    if (textFont == null || boldTextFont == null) {
        System.out.println("the runtime ships no JetBrains Mono: the text scenes do not run");
        sheets.remove(RenderConformanceScenes.Sheet.TEXT);
        sheets.remove(RenderConformanceScenes.Sheet.TRANSLUCENT_TEXT);
    }
    if (colorFont == null || backend.equals("software")) {
        // the software run has nothing to compare them with: their goldens are its output in the backend runs
        System.out.println(colorFont == null ? "the system has no color font: the color text scenes do not run"
                                             : "the color text scenes run in the backend runs, against these loops");
        sheets.remove(RenderConformanceScenes.Sheet.COLOR_TEXT);
        sheets.remove(RenderConformanceScenes.Sheet.TRANSLUCENT_COLOR_TEXT);
    }

    Path goldens = Path.of(System.getProperty("test.src", "."), "golden");
    List<Result> results = new ArrayList<>();
    for (Map.Entry<RenderConformanceScenes.Sheet, List<RenderConformanceScenes.Scene>> entry : sheets.entrySet()) {
        RenderConformanceScenes.Sheet sheet = entry.getKey();
        List<RenderConformanceScenes.Scene> scenes = entry.getValue();
        BufferedImage rendered = render(backend, renderers, sheet, scenes);
        ImageIO.write(rendered, "png", new File("renderconformance-" + backend + "-" + sheet.fileName() + ".png"));
        if (generate != null) {
            writeGolden(Path.of(generate), sheet, scenes, rendered);
            continue;
        }
        Map<String, BufferedImage> golden = sheet.live()
                                            ? scenesByName(scenes, render("software", renderers, sheet, scenes))
                                            : readGolden(goldens, sheet);
        if (golden == null) {
            System.out.println("no " + sheet.fileName() + " goldens for " + platform() + ": its scenes do not run");
            continue;
        }
        for (int index = 0; index < scenes.size(); index++) {
            RenderConformanceScenes.Scene scene = scenes.get(index);
            BufferedImage expected = golden.get(scene.name());
            if (expected == null) {
                throw new RuntimeException("no golden for " + scene.name() + ": regenerate the goldens (see RenderConformanceTest)");
            }
            BufferedImage actual = sceneOf(rendered, index);
            RenderConformanceScenes.Tolerance tolerance = backend.equals("software")
                                                  ? RenderConformanceScenes.softwareTolerance(scene)
                                                  : RenderConformanceScenes.tolerance(scene);
            results.add(compare(scene, tolerance, RenderConformanceScenes.knownDeviation(backend, scene), expected, actual));
        }
    }
    if (generate != null) {
        System.out.println("goldens written to " + generate);
        return;
    }
    writeReport(backend, results);
    List<Result> failures = results.stream().filter(Result::failed).toList();
    List<Result> known = results.stream().filter(result -> result.knownDeviation() != null).toList();
    System.out.printf("%s: %d scenes, %d failed, %d known deviations (%d of them within the tolerance)%n", backend,
                      results.size(), failures.size(), known.size(), known.stream().filter(Result::passed).count());
    known.forEach(result -> System.out.println("  known: " + describe(result) + ": " + result.knownDeviation()));
    if (!failures.isEmpty()) {
        writeFailures(backend, failures);
        StringBuilder message = new StringBuilder(failures.size() + " of " + results.size() + " scenes differ from their goldens:");
        failures.stream().limit(MAX_FAILURES_SHOWN).forEach(result -> message.append("\n  ").append(describe(result)));
        throw new RuntimeException(message.toString());
    }
}

// --- rendering

/** Windows' color emoji font, or null where the system has none (the color text scenes do not run). */
private static Font systemColorFont() {
    Font font = new Font("Segoe UI Emoji", Font.PLAIN, 16);
    return font.getFamily(Locale.ROOT).equals("Segoe UI Emoji") ? font : null;
}

/**
 * `scenes` drawn on a sheet by `backend`, by the renderer for its volatile source image (a buffered image for the
 * software loops).
 */
private static BufferedImage render(String backend, Function<Image, RenderConformanceScenes> renderers,
                                    RenderConformanceScenes.Sheet sheet, List<RenderConformanceScenes.Scene> scenes) {
    int width = RenderConformanceScenes.COLUMNS * RenderConformanceScenes.SCENE_SIZE;
    int height = RenderConformanceScenes.sheetHeight(scenes.size());
    if (backend.equals("software")) {
        RenderConformanceScenes renderer = renderers.apply(bufferedSource());
        if (!sheet.translucent()) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            paint(image.createGraphics(), renderer, scenes);
            return image;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        paint(image.createGraphics(), renderer, scenes);
        return straightAlpha(image);
    }
    GraphicsConfiguration graphicsConfig =
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    int transparency = sheet.translucent() ? Transparency.TRANSLUCENT : Transparency.OPAQUE;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(width, height, transparency);
        requireBackend(backend, image);
        VolatileImage source = graphicsConfig.createCompatibleVolatileImage(
                RenderConformanceScenes.SOURCE_IMAGE.getWidth(), RenderConformanceScenes.SOURCE_IMAGE.getHeight(),
                Transparency.TRANSLUCENT);
        // D3D reports a new image's contents lost until it has been validated once
        if (image.validate(graphicsConfig) == VolatileImage.IMAGE_INCOMPATIBLE
                || source.validate(graphicsConfig) == VolatileImage.IMAGE_INCOMPATIBLE) {
            image.flush();
            source.flush();
            continue;
        }
        Graphics2D sourceGraphics = source.createGraphics();
        sourceGraphics.setComposite(AlphaComposite.Src);
        sourceGraphics.drawImage(RenderConformanceScenes.SOURCE_IMAGE, 0, 0, null);
        sourceGraphics.dispose();
        paint(image.createGraphics(), renderers.apply(source), scenes);
        BufferedImage snapshot = image.getSnapshot();
        // validate, not contentsLost(): D3D reports the contents lost after every XOR draw (it reads the
        // surface back for them) though they are intact; a real loss restores the image here
        boolean restored = image.validate(graphicsConfig) != VolatileImage.IMAGE_OK
                           || source.validate(graphicsConfig) != VolatileImage.IMAGE_OK;
        image.flush();
        source.flush();
        if (!restored) {
            return sheet.translucent() ? straightAlpha(snapshot) : snapshot;
        }
    }
    throw new RuntimeException("the volatile image lost its contents " + MAX_ATTEMPTS + " times");
}

/** The software loops' stand-in for the backends' volatile source image: SOURCE_IMAGE in an IntArgbPre image. */
private static BufferedImage bufferedSource() {
    BufferedImage source = RenderConformanceScenes.SOURCE_IMAGE;
    BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
    Graphics2D graphics = copy.createGraphics();
    graphics.setComposite(AlphaComposite.Src);
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return copy;
}

/** `image` copied into an IntArgb image by the software loops, as the snapshot of a translucent volatile image is. */
private static BufferedImage straightAlpha(BufferedImage image) {
    BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = copy.createGraphics();
    graphics.setComposite(AlphaComposite.Src);
    graphics.drawImage(image, 0, 0, null);
    graphics.dispose();
    return copy;
}

private static void paint(Graphics2D graphics, RenderConformanceScenes renderer, List<RenderConformanceScenes.Scene> scenes) {
    try {
        renderer.paintSheet(graphics, scenes);
    } finally {
        graphics.dispose();
    }
}

/** Skips the test where the run line's pipeline is not the one rendering volatile images. */
private static void requireBackend(String backend, VolatileImage image) {
    String surface = SurfaceData.getPrimarySurfaceData(image).getClass().getName();
    String expected = switch (backend) {
        case "skia" -> "sun.java2d.skia.";
        case "d3d" -> "sun.java2d.d3d.";
        default -> throw new IllegalArgumentException("unknown backend " + backend);
    };
    if (!surface.startsWith(expected)) {
        throw new SkippedException(backend + " does not render volatile images here (" + surface + ")");
    }
}

private static BufferedImage sceneOf(BufferedImage sheet, int index) {
    return sheet.getSubimage((index % RenderConformanceScenes.COLUMNS) * RenderConformanceScenes.SCENE_SIZE,
                             (index / RenderConformanceScenes.COLUMNS) * RenderConformanceScenes.SCENE_SIZE,
                             RenderConformanceScenes.SCENE_SIZE, RenderConformanceScenes.SCENE_SIZE);
}

// --- goldens

private static String platform() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String family = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macosx" : "linux";
    return family + "-" + System.getProperty("os.arch");
}

private static Path sheetDirectory(Path root, RenderConformanceScenes.Sheet sheet) {
    return sheet.perPlatform() ? root.resolve(platform()) : root;
}

private static void writeGolden(Path root, RenderConformanceScenes.Sheet sheet, List<RenderConformanceScenes.Scene> scenes,
                                BufferedImage rendered) throws IOException {
    Path directory = sheetDirectory(root, sheet);
    Files.createDirectories(directory);
    ImageIO.write(rendered, "png", directory.resolve(sheet.fileName() + ".png").toFile());
    Files.write(directory.resolve(sheet.fileName() + ".txt"), scenes.stream().map(RenderConformanceScenes.Scene::name).toList());
}

/** The scenes of a rendered `sheet` by name, in the order of `scenes`. */
private static Map<String, BufferedImage> scenesByName(List<RenderConformanceScenes.Scene> scenes, BufferedImage sheet) {
    Map<String, BufferedImage> byName = new LinkedHashMap<>();
    for (int index = 0; index < scenes.size(); index++) {
        byName.put(scenes.get(index).name(), sceneOf(sheet, index));
    }
    return byName;
}

/** The golden scenes of `sheet` by name, or null where there are none (per-platform sheets only). */
private static Map<String, BufferedImage> readGolden(Path root, RenderConformanceScenes.Sheet sheet) throws IOException {
    Path directory = sheetDirectory(root, sheet);
    Path image = directory.resolve(sheet.fileName() + ".png");
    Path index = directory.resolve(sheet.fileName() + ".txt");
    if (!Files.isRegularFile(image) || !Files.isRegularFile(index)) {
        if (sheet.perPlatform()) {
            return null;
        }
        throw new RuntimeException("missing golden " + image);
    }
    BufferedImage golden = ImageIO.read(image.toFile());
    List<String> names = Files.readAllLines(index);
    Map<String, BufferedImage> scenes = new LinkedHashMap<>();
    for (int position = 0; position < names.size(); position++) {
        scenes.put(names.get(position), sceneOf(golden, position));
    }
    return scenes;
}

// --- comparison

/** The largest difference of two IntArgb pixels per channel, premultiplied and alpha included. */
private static int channelDifference(int first, int second) {
    int firstAlpha = first >>> 24, secondAlpha = second >>> 24;
    int difference = Math.abs(firstAlpha - secondAlpha);
    for (int shift = 0; shift <= 16; shift += 8) {
        difference = Math.max(difference, Math.abs(premultiplied(first, shift, firstAlpha)
                                                   - premultiplied(second, shift, secondAlpha)));
    }
    return difference;
}

private static int premultiplied(int pixel, int shift, int alpha) {
    return (((pixel >> shift) & 0xff) * alpha + 127) / 255;
}

private static boolean[] edges(BufferedImage golden) {
    int width = golden.getWidth(), height = golden.getHeight();
    boolean[] edge = new boolean[width * height];
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int center = golden.getRGB(x, y);
            for (int dy = -1; dy <= 1 && !edge[y * width + x]; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx >= 0 && ny >= 0 && nx < width && ny < height
                        && channelDifference(center, golden.getRGB(nx, ny)) > EDGE_CONTRAST) {
                        edge[y * width + x] = true;
                        break;
                    }
                }
            }
        }
    }
    return edge;
}

private static boolean matchesNeighbor(BufferedImage golden, int actualPixel, int x, int y, int allowance) {
    int[][] neighbors = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int[] neighbor : neighbors) {
        int nx = x + neighbor[0], ny = y + neighbor[1];
        if (nx >= 0 && ny >= 0 && nx < golden.getWidth() && ny < golden.getHeight()
            && channelDifference(golden.getRGB(nx, ny), actualPixel) <= allowance) {
            return true;
        }
    }
    return false;
}

private static Result compare(RenderConformanceScenes.Scene scene, RenderConformanceScenes.Tolerance tolerance,
                              String knownDeviation, BufferedImage golden, BufferedImage actual) {
    boolean[] edge = edges(golden);
    int interiorMax = 0, edgeMax = 0, over = 0, neighborSampled = 0;
    int[] interiorOver = new int[REPORT_LEVELS.length], edgeOver = new int[REPORT_LEVELS.length];
    for (int y = 0; y < golden.getHeight(); y++) {
        for (int x = 0; x < golden.getWidth(); x++) {
            int actualPixel = actual.getRGB(x, y);
            int difference = channelDifference(golden.getRGB(x, y), actualPixel);
            boolean onEdge = edge[y * golden.getWidth() + x];
            for (int level = 0; level < REPORT_LEVELS.length; level++) {
                if (difference > REPORT_LEVELS[level]) {
                    (onEdge ? edgeOver : interiorOver)[level]++;
                }
            }
            if (onEdge) {
                edgeMax = Math.max(edgeMax, difference);
            } else {
                interiorMax = Math.max(interiorMax, difference);
            }
            int allowance = onEdge ? tolerance.edge() : tolerance.interior();
            if (difference <= allowance) {
                continue;
            }
            if (tolerance.neighborSample() && matchesNeighbor(golden, actualPixel, x, y, allowance)) {
                neighborSampled++;
            } else {
                over++;
            }
        }
    }
    return new Result(scene, tolerance, knownDeviation, interiorMax, edgeMax, over, neighborSampled, interiorOver,
                      edgeOver, golden, actual);
}

// --- output

private static String describe(Result result) {
    return String.format("%s [%s]: max %d off edges, %d on edges, %d pixels over the allowance%s",
                         result.scene().name(), result.tolerance().name(),
                         result.interiorMax(), result.edgeMax(), result.over(),
                         result.neighborSampled() > 0 ? ", " + result.neighborSampled() + " sampled a neighbor" : "");
}

private static void writeReport(String backend, List<Result> results) throws IOException {
    try (PrintWriter report = new PrintWriter("renderconformance-" + backend + ".tsv")) {
        StringBuilder header = new StringBuilder("scene\tsheet\ttolerance\tinteriorMax\tedgeMax\tover\tneighborSampled\tpassed");
        for (int level : REPORT_LEVELS) {
            header.append("\tinterior>").append(level);
        }
        for (int level : REPORT_LEVELS) {
            header.append("\tedge>").append(level);
        }
        header.append("\tknownDeviation");
        report.println(header);
        for (Result result : results) {
            StringBuilder row = new StringBuilder(String.format("%s\t%s\t%s\t%d\t%d\t%d\t%d\t%b", result.scene().name(),
                    result.scene().sheet().fileName(), result.tolerance().name(), result.interiorMax(), result.edgeMax(),
                    result.over(), result.neighborSampled(), result.passed()));
            for (int count : result.interiorOver()) {
                row.append('\t').append(count);
            }
            for (int count : result.edgeOver()) {
                row.append('\t').append(count);
            }
            row.append('\t').append(result.knownDeviation() != null ? result.knownDeviation() : "");
            report.println(row);
        }
    }
}

private static void writeFailures(String backend, List<Result> failures) throws IOException {
    int size = RenderConformanceScenes.SCENE_SIZE, gap = 4, shown = Math.min(failures.size(), MAX_FAILURES_SHOWN);
    BufferedImage sheet = new BufferedImage(3 * (size + gap), shown * (size + gap), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = sheet.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
    for (int row = 0; row < shown; row++) {
        Result result = failures.get(row);
        int y = row * (size + gap);
        graphics.drawImage(result.golden(), 0, y, null);
        graphics.drawImage(result.actual(), size + gap, y, null);
        BufferedImage difference = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int level = Math.min(255, DIFFERENCE_GAIN * channelDifference(result.golden().getRGB(px, py),
                                                                             result.actual().getRGB(px, py)));
                difference.setRGB(px, py, (level << 16) | (level << 8) | level);
            }
        }
        graphics.drawImage(difference, 2 * (size + gap), y, null);
    }
    graphics.dispose();
    ImageIO.write(sheet, "png", new File("renderconformance-" + backend + "-failures.png"));
}
