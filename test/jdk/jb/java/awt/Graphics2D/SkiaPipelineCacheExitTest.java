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

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: the pipeline data a Skia session compiled since its last
 *          store reaches the pipeline cache when the process exits soon after, offscreen and in a window.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan SkiaPipelineCacheExitTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaPipelineCacheExitTest
 */

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.TexturePaint;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKPipelineCache;

/**
 * Graphite's native Vulkan backend hands its pipeline data (graphite.bin, the whole file replaced)
 * to the cache when it is offered, which happens at most every 10 s while the session renders; Dawn
 * appends each blob as it is produced (dawn-&lt;adapter&gt;.bin). Each session is a child JVM on the
 * parent's Graphite API with its own cache directory: it renders a first scene, offscreen or in a
 * window, waits until the cache file has it and reports its size, then renders a second scene with
 * pipelines the first does not use and exits at once. The file must then be larger than after the
 * first scene, and a second session on the directory must find the data.
 */
public class SkiaPipelineCacheExitTest {

    /** The side of the scenes, offscreen and in the window. */
    private static final int SCENE_SIZE = 256;
    /** The child polls the cache directory this often, this many times, for the first scene's data. */
    private static final int STORE_POLLS = 50;
    private static final long STORE_POLL_MS = 100;
    /** After the first store: a Dawn blob may still be being appended. */
    private static final long STORE_SETTLE_MS = 300;
    /** How long the window's second scene gets to be painted and presented before the exit. */
    private static final int WINDOW_PAINT_MS = 300;
    /** What the child prints before the size of the first scene's cache files. */
    private static final String FIRST_SCENE_PREFIX = "first scene: ";
    /** What the child prints in check mode before the pipeline cache's counters. */
    private static final String COUNTERS_PREFIX = "counters ";

    /** The pipeline cache's counters: lookups, lookups that found data, stores (SKPipelineCache.cpp). */
    private record Counters(long loads, long hits, long stores) {
        static Counters current() {
            long[] counters = SKPipelineCache.counters();
            return new Counters(counters[PipelineComparison.PipelineCacheCounter.LOADS],
                                counters[PipelineComparison.PipelineCacheCounter.HITS],
                                counters[PipelineComparison.PipelineCacheCounter.STORES]);
        }

        /** The counters a child printed after {@link #COUNTERS_PREFIX}, or null if it printed none. */
        static Counters parse(OutputAnalyzer childOutput) {
            String line = childOutput.firstMatch(COUNTERS_PREFIX + "(\\d+) (\\d+) (\\d+)");
            if (line == null) {
                return null;
            }
            String[] parts = line.substring(COUNTERS_PREFIX.length()).split(" ");
            return new Counters(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        }

        String print() {
            return COUNTERS_PREFIX + loads + " " + hits + " " + stores;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child(args[0], Path.of(args[1]), args[2]);
            return;
        }
        PipelineComparison.requireRequestedAPI();
        boolean vulkan = PipelineComparison.runsOn("Vulkan");
        String filePrefix = vulkan ? "graphite" : "dawn-";
        List<String> errors = new ArrayList<>();
        for (String mode : List.of("offscreen", "window")) {
            // in jtreg's scratch directory, not %TEMP%
            Path cacheDirectory = Files.createTempDirectory(Path.of("."), "skia-pipeline-cache-exit").toAbsolutePath();
            try {
                OutputAnalyzer firstSession = runSession(cacheDirectory, vulkan, mode, filePrefix);
                String firstSceneBytes = firstSession.firstMatch(FIRST_SCENE_PREFIX + "(\\d+) bytes", 1);
                if (firstSceneBytes == null) {
                    errors.add(mode + ": the first scene was not stored within the child's wait");
                    continue;
                }
                long afterFirstScene = Long.parseLong(firstSceneBytes);
                long atExit = cacheSize(cacheDirectory, filePrefix);
                System.out.println(mode + ": " + filePrefix + "*.bin after the first scene " + afterFirstScene
                        + " bytes, after the exit " + atExit + " bytes");
                if (atExit <= afterFirstScene) {
                    errors.add(mode + ": what the second scene compiled did not reach the cache before the exit ("
                            + atExit + " bytes at exit, " + afterFirstScene + " after the first scene)");
                }
                OutputAnalyzer secondSession = runSession(cacheDirectory, vulkan, "check", filePrefix);
                Counters counters = Counters.parse(secondSession);
                if (counters == null) {
                    errors.add(mode + ": no counters in the second session's output");
                    continue;
                }
                System.out.println(mode + ", second session: lookups " + counters.loads() + ", hits " + counters.hits()
                        + ", stores " + counters.stores());
                if (counters.hits() == 0) {
                    errors.add(mode + ": the second session found none of the first session's data");
                }
            } finally {
                PipelineComparison.deleteRecursively(cacheDirectory);
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    private static OutputAnalyzer runSession(Path cacheDirectory, boolean vulkan, String mode, String filePrefix)
            throws Exception {
        List<String> options = new ArrayList<>(List.of("-Dsun.java2d.skia.pipelineCache=" + cacheDirectory));
        if (vulkan) {
            options.add("-Dsun.java2d.skia.graphite.api=vulkan");
        }
        List<String> command = PipelineComparison.skiaChildCommand(options, SkiaPipelineCacheExitTest.class,
                                                                   mode, cacheDirectory.toString(), filePrefix);
        OutputAnalyzer output = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        System.out.println(mode + ": " + output.getStdout().trim());
        System.out.println(mode + " (stderr): " + output.getStderr().trim());
        output.shouldHaveExitValue(0);
        return output;
    }

    /** The bytes of the cache's files of the API (prefix*.bin). */
    static long cacheSize(Path cacheDirectory, String filePrefix) {
        long size = 0;
        File[] files = cacheDirectory.toFile().listFiles();
        for (File file : files != null ? files : new File[0]) {
            if (file.getName().startsWith(filePrefix) && file.getName().endsWith(".bin")) {
                size += file.length();
            }
        }
        return size;
    }

    private static void child(String mode, Path cacheDirectory, String filePrefix) throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            throw new RuntimeException("Skia is enabled in the parent but not in the child");
        }
        System.out.println("on " + SKEnvironment.runningOn());
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        if (mode.equals("check")) {
            renderOffscreen(graphicsConfig, SkiaPipelineCacheExitTest::simpleScene);
            renderOffscreen(graphicsConfig, SkiaPipelineCacheExitTest::richScene);
            Toolkit.getDefaultToolkit().sync();
            System.out.println(Counters.current().print());
            return;
        }
        Robot robot = new Robot();
        AtomicReference<Frame> frameReference = new AtomicReference<>();
        AtomicReference<Canvas> canvasReference = new AtomicReference<>();
        boolean[] firstScene = { true };
        boolean offscreen = mode.equals("offscreen");
        if (offscreen) {
            renderOffscreen(graphicsConfig, SkiaPipelineCacheExitTest::simpleScene);
        } else {
            EventQueue.invokeAndWait(() -> {
                Frame frame = new Frame("SkiaPipelineCacheExitTest");
                Canvas canvas = new Canvas() {
                    @Override
                    public void paint(Graphics canvasGraphics) {
                        if (firstScene[0]) {
                            simpleScene((Graphics2D) canvasGraphics);
                        } else {
                            richScene((Graphics2D) canvasGraphics);
                        }
                    }
                };
                canvas.setSize(SCENE_SIZE, SCENE_SIZE);
                frame.add(canvas);
                frame.pack();
                frame.setVisible(true);
                frameReference.set(frame);
                canvasReference.set(canvas);
            });
            robot.waitForIdle();
        }
        // the first scene is stored by the first offer: the flusher's timeout flush or the present
        long size = 0;
        for (int poll = 0; poll < STORE_POLLS && (size = cacheSize(cacheDirectory, filePrefix)) == 0; poll++) {
            Thread.sleep(STORE_POLL_MS);
        }
        if (size == 0) {
            System.out.println("the first scene was not stored");
            System.exit(0);
        }
        Thread.sleep(STORE_SETTLE_MS);
        System.out.println(FIRST_SCENE_PREFIX + cacheSize(cacheDirectory, filePrefix) + " bytes");
        if (offscreen) {
            renderOffscreen(graphicsConfig, SkiaPipelineCacheExitTest::richScene);
        } else {
            EventQueue.invokeAndWait(() -> {
                firstScene[0] = false;
                canvasReference.get().repaint();
            });
            robot.waitForIdle();
            robot.delay(WINDOW_PAINT_MS);
        }
        Toolkit.getDefaultToolkit().sync();
        // exits well within 10 s of the first store
        System.exit(0);
    }

    /** Renders the scene into a volatile image and reads it back, which submits and waits for the GPU. */
    private static void renderOffscreen(GraphicsConfiguration graphicsConfig, Consumer<Graphics2D> scene) {
        PipelineComparison.rendered(graphicsConfig, SCENE_SIZE, SCENE_SIZE, scene);
    }

    private static void simpleScene(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SCENE_SIZE, SCENE_SIZE);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(10, 10, 100, 60);
    }

    /** Paints, composites, transformed images and text the simple scene does not use. */
    private static void richScene(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SCENE_SIZE, SCENE_SIZE);
        Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
        float[] fractions = { 0f, 0.3f, 0.7f, 1f };
        graphics.setPaint(new LinearGradientPaint(0, 0, 200, 50, fractions, colors, MultipleGradientPaint.CycleMethod.REFLECT));
        graphics.fillRect(0, 0, 128, 128);
        graphics.setPaint(new RadialGradientPaint(190, 60, 60, fractions, colors, MultipleGradientPaint.CycleMethod.REPEAT));
        graphics.fillRect(128, 0, 128, 128);
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        tile.setRGB(1, 1, 0xff00ff00);
        tile.setRGB(5, 3, 0x80ff0000);
        graphics.setPaint(new TexturePaint(tile, new Rectangle(0, 0, 8, 8)));
        graphics.fillRect(0, 128, 128, 64);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0x80, 0x20, 0xc0, 0x90));
        graphics.drawOval(140, 140, 100, 80);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        graphics.fillOval(20, 140, 90, 90);
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT, 0.7f));
        graphics.fillRect(60, 60, 50, 50);
        graphics.setComposite(AlphaComposite.Xor);
        graphics.fillRect(150, 150, 40, 40);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(tile, AffineTransform.getRotateInstance(0.5, 200, 30), null);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(tile, 10, 200, 40, 40, null);
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("pipeline cache", 60, 230);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.drawString("pipeline cache", 60, 250);
        graphics.setXORMode(Color.CYAN);
        graphics.fillRect(100, 100, 30, 30);
        graphics.setPaintMode();
    }
}
