/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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
 * @summary Surfaces and windows are flushed and disposed while glyph quads
 *          referencing the Metal glyph caches are pending. Nothing may crash
 *          or lose contents, and text drawn after all of it must match text
 *          drawn before.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheDisposeTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class GlyphCacheDisposeTest {

    private static final int ITERATIONS = Integer.getInteger("test.iterations", 200);

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            System.out.println("The Metal pipeline is not active, nothing to test: " + gc.getClass());
            return;
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        VolatileImage keeper = gc.createCompatibleVolatileImage(500, 50, Transparency.OPAQUE);
        drawText(keeper, font, "Reference text drawn before any disposal 0123456789");
        BufferedImage reference = keeper.getSnapshot();

        JFrame frame = null;
        for (int i = 0; i < ITERATIONS; i++) {
            // a surface with pending text, flushed or dropped without a flush
            VolatileImage image = gc.createCompatibleVolatileImage(400 + i % 7, 60, Transparency.OPAQUE);
            drawText(image, new Font(Font.SANS_SERIF, Font.PLAIN, 9 + i % 24), "Disposable text " + i);
            if (i % 2 == 0) {
                image.flush();
            }
            if (i % 3 == 0) {
                image.getSnapshot();
            }
            // a window surface created and disposed with text in flight
            if (i % 50 == 0) {
                final JFrame[] created = new JFrame[1];
                SwingUtilities.invokeAndWait(() -> {
                    created[0] = new JFrame("GlyphCacheDisposeTest " + Thread.currentThread().getId());
                    created[0].setSize(300, 120);
                    created[0].setVisible(true);
                });
                frame = created[0];
                Graphics2D g = (Graphics2D) frame.getGraphics();
                if (g != null) {
                    g.setFont(font);
                    g.drawString("Window text " + i, 20, 60);
                    g.dispose();
                }
            }
            if (i % 50 == 25 && frame != null) {
                final JFrame toDispose = frame;
                SwingUtilities.invokeAndWait(toDispose::dispose);
                frame = null;
            }
            if (i % 40 == 39) {
                System.gc();
            }
        }
        if (frame != null) {
            final JFrame toDispose = frame;
            SwingUtilities.invokeAndWait(toDispose::dispose);
        }
        System.gc();
        Thread.sleep(200);

        drawText(keeper, font, "Reference text drawn before any disposal 0123456789");
        int diff = countDifferentPixels(reference, keeper.getSnapshot());
        if (diff != 0) {
            throw new RuntimeException("Text changed after surfaces were disposed: " + diff + " pixels differ");
        }
        if (keeper.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        System.out.println("PASSED");
    }

    private static void drawText(VolatileImage image, Font font, String text) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(text, 10, 35);
        } finally {
            g.dispose();
        }
    }

    static int countDifferentPixels(BufferedImage a, BufferedImage b) {
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    diff++;
                }
            }
        }
        return diff;
    }
}
