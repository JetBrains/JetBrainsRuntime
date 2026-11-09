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
 * @summary Regression test for JBR-10654: a corrupt pipeline-cache file does not take the process
 *          down, and is replaced by valid data.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaPipelineCacheCorruptTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan SkiaPipelineCacheCorruptTest
 */

import java.awt.Canvas;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import sun.java2d.skia.SKEnvironment;

/**
 * Each session is a child JVM on the parent's Graphite API. Dawn's blob file (dawn-*.bin, records
 * of a key size, a value size, the key and the value) whose first record claims 0x7fffffff bytes
 * for its key and its value is dropped and started over: the session renders and exits normally,
 * and the file holds valid records again afterwards. Before, the store allocated what the header
 * claimed (a C++ exception out of a JNI entry, the process aborted at every start) or kept the bad
 * record in front of every record appended after it. Graphite's Vulkan pipeline data
 * (graphite.bin, one blob that Graphite checks before it hands it to the driver) of the same bytes
 * is not used, and the session's own data replaces it (on the window's present).
 */
public class SkiaPipelineCacheCorruptTest {

    /** What the corrupt record's header claims for its key and its value: 2 GB each. */
    private static final int CLAIMED_SIZE = 0x7fffffff;
    /** A Dawn record header: two little-endian ints, the key size and the value size (SKPipelineCache.cpp). */
    private static final int RECORD_HEADER_BYTES = 8;
    /** Garbage after the header, far less than the header claims. */
    private static final int GARBAGE_BYTES = 64;
    private static final byte GARBAGE_BYTE = (byte) 0x5a;

    /** The side of the scene the child renders offscreen and in its window. */
    private static final int SCENE_SIZE = 64;
    /** A pixel inside the blue rectangle of the scene, and its color. */
    private static final int PROBE_X = 20;
    private static final int PROBE_Y = 20;
    private static final int BLUE = 0x0000ff;
    /** How long the child's window stays up, for its present to offer Graphite's pipeline data to the cache. */
    private static final int WINDOW_SHOWN_MS = 500;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child();
            return;
        }
        PipelineComparison.requireRequestedAPI();
        boolean vulkan = PipelineComparison.runsOn("Vulkan");
        // in jtreg's scratch directory, not %TEMP%
        Path cacheDirectory = Files.createTempDirectory(Path.of("."), "skia-pipeline-cache-corrupt").toAbsolutePath();
        try {
            runSession(cacheDirectory, vulkan, "first");
            List<File> cacheFiles = cacheFiles(cacheDirectory, vulkan);
            if (cacheFiles.isEmpty()) {
                throw new RuntimeException("no " + (vulkan ? "graphite.bin" : "dawn-*.bin") + " after a session");
            }
            // a record header claiming 2 GB for key and value, then a little garbage
            ByteBuffer corrupt = ByteBuffer.allocate(RECORD_HEADER_BYTES + GARBAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            corrupt.putInt(CLAIMED_SIZE).putInt(CLAIMED_SIZE);
            while (corrupt.hasRemaining()) {
                corrupt.put(GARBAGE_BYTE);
            }
            for (File cacheFile : cacheFiles) {
                Files.write(cacheFile.toPath(), corrupt.array());
                System.out.println("corrupted " + cacheFile.getName());
            }
            OutputAnalyzer corruptSession = runSession(cacheDirectory, vulkan, "corrupt");
            corruptSession.shouldContain("rendered ok");
            for (File cacheFile : cacheFiles) {
                byte[] bytes = Files.exists(cacheFile.toPath()) ? Files.readAllBytes(cacheFile.toPath()) : new byte[0];
                System.out.println(cacheFile.getName() + " after the session: " + bytes.length + " bytes");
                if (bytes.length >= Integer.BYTES
                        && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == CLAIMED_SIZE) {
                    throw new RuntimeException(cacheFile.getName() + " still starts with the corrupt record");
                }
                if (vulkan && (bytes.length == 0 || Arrays.equals(bytes, corrupt.array()))) {
                    throw new RuntimeException(cacheFile.getName() + " was not replaced by the session's pipeline data");
                }
            }
            // and a third session reads what the second one stored
            runSession(cacheDirectory, vulkan, "third").shouldContain("rendered ok");
        } finally {
            PipelineComparison.deleteRecursively(cacheDirectory);
        }
        System.out.println("PASSED");
    }

    /** The API's cache files in the directory: graphite.bin on Vulkan, dawn-*.bin on Dawn. */
    private static List<File> cacheFiles(Path cacheDirectory, boolean vulkan) {
        List<File> result = new ArrayList<>();
        File[] files = cacheDirectory.toFile().listFiles();
        for (File file : files != null ? files : new File[0]) {
            System.out.println("  " + file.getName() + ": " + file.length() + " bytes");
            String name = file.getName();
            if (vulkan ? name.equals("graphite.bin") : name.startsWith("dawn-") && name.endsWith(".bin")) {
                result.add(file);
            }
        }
        return result;
    }

    private static OutputAnalyzer runSession(Path cacheDirectory, boolean vulkan, String label) throws Exception {
        List<String> options = new ArrayList<>(List.of("-Dsun.java2d.skia.pipelineCache=" + cacheDirectory));
        if (vulkan) {
            options.add("-Dsun.java2d.skia.graphite.api=vulkan");
        }
        List<String> command = PipelineComparison.skiaChildCommand(options, SkiaPipelineCacheCorruptTest.class, label);
        OutputAnalyzer output = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        System.out.println(label + ": exit " + output.getExitValue() + ", " + output.getStdout().trim());
        output.shouldHaveExitValue(0);
        return output;
    }

    /** Renders a few primitives offscreen, checks one pixel, and presents them in a window. */
    private static void child() throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            throw new RuntimeException("Skia is enabled in the parent but not in the child");
        }
        BufferedImage snapshot = PipelineComparison.rendered(PipelineComparison.defaultConfiguration(),
                                                             SCENE_SIZE, SCENE_SIZE, SkiaPipelineCacheCorruptTest::paint);
        int rgb = snapshot.getRGB(PROBE_X, PROBE_Y) & 0xffffff;
        if (rgb != BLUE) {
            throw new RuntimeException("expected blue at " + PROBE_X + "," + PROBE_Y + ", got " + Integer.toHexString(rgb));
        }
        // a window's present offers Graphite's Vulkan pipeline data to the cache
        AtomicReference<Frame> frameReference = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame("SkiaPipelineCacheCorruptTest");
            Canvas canvas = new Canvas() {
                @Override
                public void paint(Graphics canvasGraphics) {
                    SkiaPipelineCacheCorruptTest.paint((Graphics2D) canvasGraphics);
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
        System.out.println("rendered ok");
    }

    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, SCENE_SIZE, SCENE_SIZE);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(8, 8, 32, 32);
        graphics.fillOval(30, 30, 30, 30);
        graphics.drawString("x", 5, 60);
    }
}
