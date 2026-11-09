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
 * @summary Regression test for JBR-10654: off-screen work a direct render-queue entry leaves held is
 *          ended by the flusher's timeout flush, as that of a buffer flush is.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.awt java.desktop/sun.java2d java.desktop/sun.java2d.loops
 *          java.desktop/sun.java2d.skia:+open
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDirectEntryHeldTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaDirectEntryHeldTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Toolkit;
import java.awt.image.VolatileImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import sun.awt.SunToolkit;
import sun.java2d.SunGraphics2D;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.MaskFill;
import sun.java2d.loops.SurfaceType;

/**
 * Operations larger than the render buffer (6.4 MB) run as direct entries on the flusher
 * (BufferedRenderPipe.drawPoly, BufferedMaskFill, BufferedTextPipe: flushAndInvokeNow). A flush
 * that drew off-screen only holds its recordings for the next one and tells the queue, whose
 * flusher then ends them with its 100 ms timeout flush; a direct entry ends its own flush the
 * same way, but its result was dropped, so held off-screen work waited for whatever flushed next.
 * Each case draws one direct entry into a VolatileImage while holding the queue lock (the AWT
 * lock: no timeout flush can run in between) and reads whether the queue knows of held work.
 */
public class SkiaDirectEntryHeldTest {

    /** The side of the volatile image the direct entries draw into. */
    private static final int IMAGE_SIZE = 256;
    /** Points of the polyline, 8 bytes each: well over the render buffer's 6.4 MB. */
    private static final int POLYLINE_POINTS = 1_000_000;
    /** Glyphs of the string, 8 bytes each: well over the render buffer. */
    private static final int GLYPHS = 1_000_000;
    /** The mask's size: 8 MB of coverage, mostly clipped. */
    private static final int MASK_WIDTH = 2048;
    private static final int MASK_HEIGHT = 4096;
    /** Half coverage in every mask byte. */
    private static final byte MASK_COVERAGE = (byte) 0x80;
    /** How long the flusher's 100 ms timeout flush gets to end the held recordings. */
    private static final long TIMEOUT_FLUSH_WAIT_MS = 5000;
    private static final long POLL_MS = 20;

    public static void main(String[] args) throws Exception {
        PipelineComparison.requireRequestedAPI();   // names the API it runs on: the Vulkan run line runs on Vulkan
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        Method getInstance = Class.forName("sun.java2d.skia.SKRenderQueue").getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object queue = getInstance.invoke(null);
        Field pendingPresent = queue.getClass().getDeclaredField("pendingPresent");
        pendingPresent.setAccessible(true);

        List<String> failures = new ArrayList<>();
        check(graphicsConfig, queue, pendingPresent, "drawPoly", graphics -> {
            int[] xPoints = new int[POLYLINE_POINTS];
            int[] yPoints = new int[POLYLINE_POINTS];
            for (int point = 0; point < POLYLINE_POINTS; point++) {
                xPoints[point] = 10 + (point & 1);
                yPoints[point] = 10 + ((point >> 1) & 7);
            }
            graphics.drawPolyline(xPoints, yPoints, POLYLINE_POINTS);
        }, failures);
        check(graphicsConfig, queue, pendingPresent, "maskFill", graphics -> {
            SunGraphics2D sunGraphics = (SunGraphics2D) graphics;
            MaskFill maskFill = MaskFill.getFromCache(SurfaceType.AnyColor, CompositeType.SrcOver,
                                                      sunGraphics.surfaceData.getSurfaceType());
            if (maskFill == null || !maskFill.getClass().getName().equals("sun.java2d.skia.SKMaskFill")) {
                throw new RuntimeException("not the Skia mask fill: " + maskFill);
            }
            byte[] mask = new byte[MASK_WIDTH * MASK_HEIGHT];
            Arrays.fill(mask, MASK_COVERAGE);
            maskFill.MaskFill(sunGraphics, sunGraphics.surfaceData, sunGraphics.composite, 0, 0, MASK_WIDTH, MASK_HEIGHT,
                              mask, 0, MASK_WIDTH);
        }, failures);
        check(graphicsConfig, queue, pendingPresent, "drawGlyphList", graphics -> {
            graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
            graphics.drawString("i".repeat(GLYPHS), 0, 20);
        }, failures);
        if (!failures.isEmpty()) {
            throw new RuntimeException(String.join("; ", failures));
        }
        System.out.println("PASSED");
    }

    private static void check(GraphicsConfiguration graphicsConfig, Object queue, Field pendingPresent, String name,
                              Consumer<Graphics2D> draw, List<String> failures) throws Exception {
        VolatileImage volatileImage = graphicsConfig.createCompatibleVolatileImage(IMAGE_SIZE, IMAGE_SIZE);
        volatileImage.validate(graphicsConfig);
        Graphics2D graphics = volatileImage.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        graphics.setColor(Color.BLACK);
        Toolkit.getDefaultToolkit().sync();   // nothing held from before
        boolean held;
        SunToolkit.awtLock();
        try {
            draw.accept(graphics);
            held = pendingPresent.getBoolean(queue);
        } finally {
            SunToolkit.awtUnlock();
        }
        graphics.dispose();
        System.out.println(name + ": the queue knows of held work: " + held);
        if (!held) {
            failures.add(name + ": the direct entry's held recordings are unknown to the queue");
        }
        // the timeout flush ends them
        long deadline = System.nanoTime() + TIMEOUT_FLUSH_WAIT_MS * 1_000_000;
        while (pendingPresent.getBoolean(queue) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS);
        }
        if (pendingPresent.getBoolean(queue)) {
            failures.add(name + ": the held recordings were not ended within " + TIMEOUT_FLUSH_WAIT_MS + " ms");
        }
        volatileImage.flush();
    }
}
