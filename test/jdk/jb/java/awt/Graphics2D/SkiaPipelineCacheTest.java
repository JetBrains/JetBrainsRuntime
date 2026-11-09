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
 * @summary Regression test for JBR-10654: the Skia backend keeps what its GPU backend compiled in the
 *          directory sun.java2d.skia.pipelineCache names, and a second session finds it.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaPipelineCacheTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan SkiaPipelineCacheTest
 */

import java.awt.Canvas;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKPipelineCache;

/**
 * Two layers, in one directory (SKPipelineCache.h): Dawn hands over the shaders and pipelines it
 * compiled as keyed blobs (dawn-&lt;adapter&gt;.bin, appended per blob), Graphite's native Vulkan
 * backend its VkPipelineCache data (graphite.bin, the whole file replaced), which it offers after a
 * flush (at most every 10 s), when a configuration is disposed and at exit. Each session is a
 * child JVM on the parent's Graphite API that renders a scene into a VolatileImage and then into a
 * window. The first session stores data and leaves the API's file, a second session finds it and,
 * for the offscreen scene (the window's present compiles what timing makes it), stores less than
 * the first (fewer Dawn blobs; Vulkan's unchanged data not again), and with the property off
 * nothing is looked up or stored.
 */
public class SkiaPipelineCacheTest {

    /** The side of the scene, offscreen and in the window. */
    private static final int SCENE_SIZE = 256;
    /** How long the child's window stays up, for its present to offer Graphite's pipeline data to the cache. */
    private static final int WINDOW_SHOWN_MS = 500;
    /** What the child prints before its counters after the offscreen scene, and at its end. */
    private static final String OFFSCREEN_COUNTERS_PREFIX = "offscreen counters";
    private static final String COUNTERS_PREFIX = "counters";

    /** The pipeline cache's counters: lookups, lookups that found data, stores (SKPipelineCache.cpp). */
    private record Counters(long loads, long hits, long stores) {
        static Counters current() {
            long[] counters = SKPipelineCache.counters();
            return new Counters(counters[PipelineComparison.PipelineCacheCounter.LOADS],
                                counters[PipelineComparison.PipelineCacheCounter.HITS],
                                counters[PipelineComparison.PipelineCacheCounter.STORES]);
        }

        /** The counters the child printed on the line starting with the prefix. */
        static Counters parse(OutputAnalyzer childOutput, String prefix, String label) {
            String line = childOutput.firstMatch("(?m)^" + prefix + " (\\d+) (\\d+) (\\d+)$");
            if (line == null) {
                throw new RuntimeException(label + ": no " + prefix + " in the child's output");
            }
            String[] parts = line.split(" ");
            int first = parts.length - 3;
            return new Counters(Long.parseLong(parts[first]), Long.parseLong(parts[first + 1]), Long.parseLong(parts[first + 2]));
        }

        String print(String prefix) {
            return prefix + " " + loads + " " + hits + " " + stores;
        }

        @Override
        public String toString() {
            return "lookups " + loads + ", hits " + hits + ", stores " + stores;
        }
    }

    /** One session's counters: at its end, and after its offscreen scene alone. */
    private record Session(Counters atEnd, Counters afterOffscreen) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child();
            return;
        }
        PipelineComparison.requireRequestedAPI();
        boolean vulkan = PipelineComparison.runsOn("Vulkan");
        String filePrefix = vulkan ? "graphite" : "dawn-";
        // in jtreg's scratch directory, not %TEMP%
        Path cacheDirectory = Files.createTempDirectory(Path.of("."), "skia-pipeline-cache").toAbsolutePath();
        Session first;
        Session second;
        Session off;
        int fileCount = 0;
        try {
            first = runSession(cacheDirectory, vulkan, "first");
            second = runSession(cacheDirectory, vulkan, "second");
            off = runSession(null, vulkan, "off");
            File[] files = cacheDirectory.toFile().listFiles();
            for (File file : files != null ? files : new File[0]) {
                System.out.println("  " + file.getName() + ": " + file.length() + " bytes");
                if (file.getName().startsWith(filePrefix) && file.getName().endsWith(".bin") && file.length() > 0) {
                    fileCount++;
                }
            }
        } finally {
            PipelineComparison.deleteRecursively(cacheDirectory);
        }
        System.out.println("cache files (" + filePrefix + "*.bin) after two sessions: " + fileCount);
        List<String> errors = new ArrayList<>();
        if (first.atEnd().stores() == 0 || fileCount == 0) {
            errors.add("the first session stored nothing (" + first.atEnd().stores() + " stores, " + fileCount + " "
                       + filePrefix + "*.bin files)");
        }
        if (second.atEnd().hits() == 0) {
            errors.add("the second session found none of the first session's data (" + second.atEnd().loads() + " lookups)");
        }
        // the offscreen scene, the same in both sessions: what the window's present compiles depends on
        // timing (repaints, the swap chain), the scene does not
        if (second.afterOffscreen().stores() >= first.afterOffscreen().stores()) {
            errors.add("the second session's offscreen scene stored as much as the first's ("
                       + second.afterOffscreen().stores() + " vs " + first.afterOffscreen().stores() + ")");
        }
        if (off.atEnd().loads() != 0 || off.atEnd().stores() != 0) {
            errors.add("with the cache off there were " + off.atEnd().loads() + " lookups and " + off.atEnd().stores() + " stores");
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    /** One session: a child JVM renders the scene and reports its counters (lookups, hits, stores). */
    private static Session runSession(Path cacheDirectory, boolean vulkan, String label) throws Exception {
        List<String> options = new ArrayList<>(List.of(
                "-Dsun.java2d.skia.pipelineCache=" + (cacheDirectory != null ? cacheDirectory.toString() : "off")));
        if (vulkan) {
            options.add("-Dsun.java2d.skia.graphite.api=vulkan");
        }
        List<String> command = PipelineComparison.skiaChildCommand(options, SkiaPipelineCacheTest.class, label);
        OutputAnalyzer output = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        System.out.println(label + ": " + output.getStdout().trim());
        output.shouldHaveExitValue(0);
        Session session = new Session(Counters.parse(output, COUNTERS_PREFIX, label),
                                      Counters.parse(output, OFFSCREEN_COUNTERS_PREFIX, label));
        System.out.println(label + ": " + session.atEnd() + " (after the offscreen scene: " + session.afterOffscreen() + ")");
        return session;
    }

    /** Draws enough kinds of primitives for a dozen pipelines, offscreen and in a window, and prints the counters. */
    private static void child() throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            throw new RuntimeException("Skia is enabled in the parent but not in the child");
        }
        System.out.println("on " + SKEnvironment.runningOn());
        // the read-back flushes and waits for the GPU
        PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), SCENE_SIZE, SCENE_SIZE, SkiaPipelineCacheTest::paint);
        System.out.println(Counters.current().print(OFFSCREEN_COUNTERS_PREFIX));

        // a window's present offers Graphite's Vulkan pipeline data to the cache
        AtomicReference<Frame> frameReference = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame("SkiaPipelineCacheTest");
            Canvas canvas = new Canvas() {
                @Override
                public void paint(Graphics canvasGraphics) {
                    SkiaPipelineCacheTest.paint((Graphics2D) canvasGraphics);
                }
            };
            canvas.setSize(SCENE_SIZE, SCENE_SIZE);
            frame.add(canvas);
            frame.pack();
            frame.setVisible(true);
            frameReference.set(frame);
        });
        Robot robot = new Robot();
        robot.waitForIdle();
        robot.delay(WINDOW_SHOWN_MS);
        EventQueue.invokeAndWait(() -> frameReference.get().dispose());
        System.out.println(Counters.current().print(COUNTERS_PREFIX));
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SCENE_SIZE, SCENE_SIZE);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(10, 10, 100, 60);
        graphics.drawLine(0, 0, 255, 200);
        graphics.setPaint(new GradientPaint(0, 0, Color.RED, SCENE_SIZE, SCENE_SIZE, Color.GREEN));
        graphics.fillRect(120, 10, 120, 120);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.BLACK);
        graphics.fillOval(20, 100, 120, 80);
        graphics.drawOval(150, 150, 90, 90);
        graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString("pipeline cache", 20, 220);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.drawString("pipeline cache", 20, 240);
        BufferedImage sprite = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        sprite.setRGB(5, 5, 0x80ff8000);
        graphics.drawImage(sprite, 200, 20, null);
    }
}
