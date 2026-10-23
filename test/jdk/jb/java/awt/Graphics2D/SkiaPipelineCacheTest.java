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
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary The Skia backend keeps what its GPU backend compiled (Dawn's shaders
 *          and pipelines, Graphite's Vulkan pipeline cache) in the directory
 *          sun.java2d.skia.pipelineCache names: the first session of a
 *          process stores blobs there, a second session finds them and stores
 *          nothing new, and with the property off nothing is looked up or
 *          written. When Skia is unavailable there is nothing to test.
 * @key headful
 * @library /test/lib
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SkiaPipelineCacheTest
 */
public class SkiaPipelineCacheTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child();
            return;
        }
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        Path dir = Files.createTempDirectory("skia-pipeline-cache");
        long[] first, second, off;
        int fileCount;
        try {
            first = run(dir, "first");
            second = run(dir, "second");
            off = run(null, "off");
            File[] files = dir.toFile().listFiles();
            fileCount = files != null ? files.length : 0;
            for (File f : files != null ? files : new File[0]) {
                System.out.println("  " + f.getName() + ": " + f.length() + " bytes");
            }
        } finally {
            try (var entries = Files.walk(dir)) {
                entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println("cache files after two sessions: " + fileCount);
        List<String> errors = new ArrayList<>();
        if (first[2] == 0 || fileCount == 0) {
            errors.add("the first session stored nothing (" + first[2] + " blobs, " + fileCount + " files)");
        }
        if (second[1] == 0) {
            errors.add("the second session found none of the first session's blobs (" + second[0] + " lookups)");
        }
        if (second[2] >= first[2]) {
            errors.add("the second session stored as much as the first (" + second[2] + " vs " + first[2] + ")");
        }
        if (off[0] != 0 || off[2] != 0) {
            errors.add("with the cache off there were " + off[0] + " lookups and " + off[2] + " stores");
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    /** One session: a child JVM renders the scene and reports its counters (lookups, hits, stores). */
    private static long[] run(Path cache, String label) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.desktop/sun.java2d.skia=ALL-UNNAMED",
                "-Dsun.java2d.skia=true",
                "-Dsun.java2d.skia.pipelineCache=" + (cache != null ? cache.toString() : "off"),
                SkiaPipelineCacheTest.class.getName(), label));
        OutputAnalyzer out = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        out.shouldHaveExitValue(0);
        String line = out.firstMatch("counters (\\d+) (\\d+) (\\d+)");
        if (line == null) {
            throw new RuntimeException(label + ": no counters in the child's output");
        }
        String[] parts = line.split(" ");
        long[] counters = { Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]) };
        System.out.println(label + ": lookups " + counters[0] + ", hits " + counters[1] + ", stores " + counters[2]);
        return counters;
    }

    /** Draws enough kinds of primitives for a dozen pipelines and prints the counters. */
    private static void child() {
        if (!SKEnvironment.isSkiaEnabled()) {
            throw new RuntimeException("Skia is enabled in the parent but not in the child");
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage image = gc.createCompatibleVolatileImage(256, 256);
        image.validate(gc);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 256, 256);
        g.setColor(Color.BLUE);
        g.fillRect(10, 10, 100, 60);
        g.drawLine(0, 0, 255, 200);
        g.setPaint(new GradientPaint(0, 0, Color.RED, 256, 256, Color.GREEN));
        g.fillRect(120, 10, 120, 120);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillOval(20, 100, 120, 80);
        g.drawOval(150, 150, 90, 90);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawString("pipeline cache", 20, 220);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.drawString("pipeline cache", 20, 240);
        BufferedImage sprite = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        sprite.setRGB(5, 5, 0x80ff8000);
        g.drawImage(sprite, 200, 20, null);
        g.dispose();
        image.getSnapshot();   // flushes and waits for the GPU
        long[] c = SKEnvironment.pipelineCacheCounters();
        System.out.println("counters " + c[0] + " " + c[1] + " " + c[2]);
    }
}
