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

import java.awt.*;
import java.awt.image.VolatileImage;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/**
 * @test
 * bug JBR-10282
 * @key headful
 * @summary Verifies that drawString on an invalidated surface does not throw
 *          ClassCastException (NullSurfaceData cannot be cast to
 *          MTLSurfaceData/OGLSurfaceData). When a surface is invalidated
 *          during a paint cycle, the pipeline should handle it gracefully
 *          via InvalidPipeException rather than leaking a ClassCastException.
 *
 *          This test simulates the race by replacing the surfaceData field
 *          with NullSurfaceData via reflection while the text pipe is still
 *          set to the accelerated (Metal/OGL) pipeline — exactly what happens
 *          when a window is hidden or a display changes mid-paint.
 *
 * @run main/othervm --add-opens java.desktop/sun.java2d=ALL-UNNAMED
 *                   MTLTextRendererNullSurfaceTest
 */
public class MTLTextRendererNullSurfaceTest {

    private static final AtomicReference<Throwable> caughtException = new AtomicReference<>();

    public static void main(String[] args) throws Exception {
        testVolatileImagePath();

        Throwable ex = caughtException.get();
        if (ex != null) {
            throw new RuntimeException(
                "ClassCastException thrown during text rendering on " +
                "invalidated surface. The accelerated text renderer " +
                "(MTLTextRenderer/OGLTextRenderer) does not handle " +
                "NullSurfaceData. Fix: use SurfaceData.convertTo() " +
                "instead of direct cast in validateContext().",
                ex);
        }

        System.out.println("PASSED: no ClassCastException from text rendering " +
                           "on invalidated surface");
    }

    /**
     * Creates a VolatileImage (which uses the accelerated pipeline),
     * renders text to prime the pipeline, then replaces surfaceData
     * with NullSurfaceData via reflection to simulate mid-paint
     * invalidation, and attempts to draw text again.
     *
     * The real race scenario: EDT is inside BufferedTextPipe.drawGlyphList
     * → MTLTextRenderer.validateContext when another thread replaces
     * surfaceData with NullSurfaceData (due to window hide, display change,
     * peer disposal). The test simulates this by doing the replacement
     * between two drawString calls without calling invalidatePipe().
     */
    private static void testVolatileImagePath() throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();

        VolatileImage vi = gc.createCompatibleVolatileImage(400, 200);
        Graphics2D g2d = vi.createGraphics();

        try {
            g2d.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
            g2d.setColor(Color.BLACK);

            // Draw text to prime the accelerated text pipeline
            g2d.drawString("Priming the pipeline", 10, 30);

            // Now simulate the race: replace surfaceData with NullSurfaceData
            // via reflection, WITHOUT calling invalidatePipe(). This is
            // exactly what happens when SurfaceData.getReplacement() returns
            // null during a concurrent invalidation — the surfaceData field
            // is set to NullSurfaceData but the pipe references are stale.
            Field surfaceDataField = g2d.getClass().getField("surfaceData");
            // Get NullSurfaceData.theInstance via reflection
            Class<?> nullSdClass = Class.forName("sun.java2d.NullSurfaceData");
            Field theInstanceField = nullSdClass.getField("theInstance");
            Object nullSurface = theInstanceField.get(null);

            // Replace the surface — simulates the race condition
            surfaceDataField.set(g2d, nullSurface);

            // Now attempt to draw text. The text pipe is still set to
            // MTLTextRenderer (from the priming draw above), but the
            // surfaceData is NullSurfaceData. MTLTextRenderer.validateContext
            // will cast surfaceData to MTLSurfaceData — this MUST NOT throw
            // ClassCastException. It should throw InvalidPipeException which
            // SunGraphics2D.drawString catches and handles via revalidateAll().
            try {
                g2d.drawString("After invalidation", 10, 60);
            } catch (ClassCastException e) {
                caughtException.compareAndSet(null, e);
            } catch (Exception e) {
                // InvalidPipeException, NullPointerException, etc. are
                // acceptable — they indicate the pipeline detected the
                // invalid surface state. Only ClassCastException is a bug.
                System.out.println("Expected exception after surface " +
                        "invalidation: " + e.getClass().getName() +
                        ": " + e.getMessage());
            }
        } finally {
            g2d.dispose();
            vi.flush();
        }
    }
}
