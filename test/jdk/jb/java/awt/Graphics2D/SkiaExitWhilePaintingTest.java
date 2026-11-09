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
 * @test id=d3d11
 * @key headful
 * @summary Regression test for JBR-10654: a process that exits while another thread paints with Skia
 *          exits cleanly and promptly, and no Graphite context is released during the DLL detach
 *          (Dawn over Direct3D 11).
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm/timeout=600 SkiaExitWhilePaintingTest d3d11
 */

/*
 * @test id=d3d12
 * @key headful
 * @summary Regression test for JBR-10654: as above, on Dawn over Direct3D 12.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm/timeout=600 SkiaExitWhilePaintingTest d3d12
 */

/*
 * @test id=vulkan
 * @key headful
 * @summary Regression test for JBR-10654: as above, on Vulkan.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm/timeout=600 SkiaExitWhilePaintingTest vulkan
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/**
 * A child JVM shows a window and starts a thread that paints into it and into a volatile image
 * without pause (text, fills, image draws: the flusher is busy, and flushes that present nothing
 * hold their recordings), and calls System.exit while that thread paints. The exit code must be 0
 * and the child must be gone within EXIT_TIMEOUT_S. Nothing that holds Graphite objects is released
 * at exit (SKNeverDestroyed.h): the release would run during the DLL detach, under the loader lock,
 * after the other threads were killed (it waits for the GPU, shuts the resource cache down, and
 * frees Dawn's and Vulkan's objects). The child runs with J2D_TRACE_LEVEL=3 (J2D_TRACE_INFO, the
 * lowest level that has them), so that the release traces of RELEASE_TRACES show if one of those
 * destructors runs: SKGraphicsConfig's (a context released, as by the table of the contexts),
 * SKContext's (the render state), the rect batch's and the last glyph list's. Their holders made
 * ordinary statics again would go red here. The other never-destroyed objects (the folded presents,
 * the entry lock, the Dawn instance and devices, the Vulkan device, the graphics APIs) are standard
 * or platform types without such a trace and are not covered by one. Several children per API, the
 * exit racing the painting thread differently each time.
 */
public class SkiaExitWhilePaintingTest {

    /** What the child prints when Skia did not start, for the parent to skip on. */
    private static final String NOT_ENABLED = "Skia is not enabled";

    /** The traces of the never-destroyed objects' destructors (J2D_TRACE_INFO), none of which may run at exit. */
    private static final List<String> RELEASE_TRACES = List.of(
            "SKGraphicsConfig: releasing the Graphite context",
            "SKContext: releasing the render state",
            "SKRectBatch: releasing the rect batch",
            "SKTextPipe: releasing the last glyph list");

    /** J2D_TRACE_INFO: the errors, warnings and information, the release traces among them. */
    private static final String TRACE_LEVEL_INFO = "3";

    /** Children per API. */
    private static final int ATTEMPTS = 4;

    /** How long a child may take from its start to its exit. */
    private static final long EXIT_TIMEOUT_S = 90;

    /** How long the child paints before it exits, and the painting thread's head start. */
    private static final int PAINT_BEFORE_EXIT_MS = 1500;

    /** The child's window and the volatile image it paints into as well. */
    private static final int FRAME_WIDTH = 400;
    private static final int FRAME_HEIGHT = 300;
    private static final int IMAGE_SIZE = 256;

    /** The side of the software image drawn each round (uploaded per draw, or cached as a texture). */
    private static final int SPRITE_SIZE = 24;

    /** Glyph lists per round: enough to keep the glyph batches open across flushes. */
    private static final int TEXT_LINES = 12;
    private static final int LINE_HEIGHT = 18;
    private static final int FONT_SIZE = 14;

    /** A color step per round, so that every round changes the paint. */
    private static final int COLOR_STEP = 0x010203;

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[0].equals("child")) {
            child();
            return;
        }
        String mode = args[0];
        List<String> options = switch (mode) {
            case "vulkan" -> List.of("-Dsun.java2d.skia.pipelineCache=off", "-Dsun.java2d.skia.graphite.api=vulkan");
            case "d3d12" -> List.of("-Dsun.java2d.skia.pipelineCache=off", "-Dsun.java2d.skia.dawn.api=d3d12");
            default -> List.of("-Dsun.java2d.skia.pipelineCache=off", "-Dsun.java2d.skia.dawn.api=d3d11");
        };
        String api = mode.equals("vulkan") ? "Vulkan" : "Dawn";
        String direct3D = switch (mode) {
            case "d3d12" -> "(D3D12)";
            case "d3d11" -> "(D3D11)";
            default -> null;
        };
        List<String> command = PipelineComparison.skiaChildCommand(options, SkiaExitWhilePaintingTest.class, "child", mode);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            ProcessBuilder builder = ProcessTools.createTestJavaProcessBuilder(command);
            builder.environment().put("J2D_TRACE_LEVEL", TRACE_LEVEL_INFO);
            Process child = builder.start();
            OutputAnalyzer output = new OutputAnalyzer(child, true);
            boolean exited = child.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS);
            if (!exited) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                child.waitFor();
            }
            String stdout = output.getStdout();
            String stderr = output.getStderr();
            System.out.println("attempt " + attempt + ": child exit code " + (exited ? child.exitValue() : "none"));
            System.out.println("child stdout (last lines):\n" + lastLines(stdout));
            System.out.println("child stderr (last lines):\n" + lastLines(stderr));
            if (stdout.contains(NOT_ENABLED)) {
                throw new SkippedException("Skia is not enabled: nothing to test");
            }
            String renderedOn = output.firstMatch("rendered on (.*)", 1);
            if (renderedOn != null && (!renderedOn.contains(" Graphite/" + api + " on ")
                    || (direct3D != null && !renderedOn.trim().endsWith(direct3D)))) {
                throw new SkippedException(mode + " unavailable here (" + renderedOn + "): nothing to test");
            }
            if (!exited) {
                throw new RuntimeException("attempt " + attempt + ": the child did not exit within " + EXIT_TIMEOUT_S + " s");
            }
            if (child.exitValue() != 0) {
                throw new RuntimeException("attempt " + attempt + ": the child exited with 0x"
                        + Integer.toHexString(child.exitValue()));
            }
            if (renderedOn == null) {
                throw new RuntimeException("attempt " + attempt + ": the child did not render");
            }
            for (String releaseTrace : RELEASE_TRACES) {
                if (stdout.contains(releaseTrace) || stderr.contains(releaseTrace)) {
                    throw new RuntimeException("attempt " + attempt + ": released at exit: " + releaseTrace);
                }
            }
        }
        System.out.println("PASSED");
    }

    private static String lastLines(String text) {
        String[] lines = text.split("\\R");
        int first = Math.max(0, lines.length - TEXT_LINES);
        return String.join("\n", List.of(lines).subList(first, lines.length));
    }

    private static void child() throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println(NOT_ENABLED);
            return;
        }
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        Frame frame = new Frame("SkiaExitWhilePaintingTest", graphicsConfig);
        frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
        frame.setVisible(true);
        VolatileImage image = graphicsConfig.createCompatibleVolatileImage(IMAGE_SIZE, IMAGE_SIZE);
        BufferedImage sprite = new BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D spriteGraphics = sprite.createGraphics();
        spriteGraphics.setColor(Color.ORANGE);
        spriteGraphics.fillOval(0, 0, SPRITE_SIZE, SPRITE_SIZE);
        spriteGraphics.dispose();
        Thread painter = new Thread(() -> {
            Font font = new Font(Font.DIALOG, Font.PLAIN, FONT_SIZE);
            for (int round = 0; ; round++) {
                Color color = new Color((round * COLOR_STEP) & 0xffffff);
                paint((Graphics2D) frame.getGraphics(), font, color, sprite, round);
                if (image.validate(graphicsConfig) != VolatileImage.IMAGE_INCOMPATIBLE) {
                    paint(image.createGraphics(), font, color, sprite, round);
                }
            }
        }, "painter");
        painter.setDaemon(true);
        painter.start();
        Thread.sleep(PAINT_BEFORE_EXIT_MS);
        System.out.println("rendered on " + SKEnvironment.runningOn());
        System.out.flush();
        System.exit(0);
    }

    private static void paint(Graphics2D graphics, Font font, Color color, BufferedImage sprite, int round) {
        if (graphics == null) {
            return;
        }
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT);
            graphics.setColor(Color.WHITE);
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    round % 2 == 0 ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int line = 0; line < TEXT_LINES; line++) {
                graphics.drawString("round " + round + " line " + line, 4, (line + 1) * LINE_HEIGHT);
            }
            graphics.drawImage(sprite, round % FRAME_WIDTH, round % FRAME_HEIGHT, null);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.fillOval(round % FRAME_WIDTH, 0, SPRITE_SIZE, SPRITE_SIZE);
        } finally {
            graphics.dispose();
        }
    }
}
