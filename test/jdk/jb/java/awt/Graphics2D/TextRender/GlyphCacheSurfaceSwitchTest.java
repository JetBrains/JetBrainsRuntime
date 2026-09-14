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
 * @summary Text rendering must stay correct and fast when the destination
 *          surface changes between draws (Metal pipeline). Before the fix,
 *          every surface switch freed both glyph caches and waited for the
 *          GPU, which stalled the EDT in MTLRenderQueue.flushNow.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true GlyphCacheSurfaceSwitchTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class GlyphCacheSurfaceSwitchTest {

    private static final int SURFACES = Integer.getInteger("test.surfaces", 8);
    private static final int ITERATIONS = Integer.getInteger("test.iterations", 300);
    // A surface switch must not cost a GPU round trip. The bound is generous
    // so that only the old synchronous wait per switch can exceed it.
    private static final double MAX_MS_PER_SWITCH =
            Double.parseDouble(System.getProperty("test.maxMsPerSwitch", "4.0"));

    private static final String TEXT = "The quick brown fox jumps over the lazy dog 0123456789";
    private static final int W = 420;
    private static final int H = 40;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            System.out.println("The Metal pipeline is not active, nothing to test: " + gc.getClass());
            return;
        }

        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("GlyphCacheSurfaceSwitchTest");
            frame[0].setSize(W + 40, H * 3);
            frame[0].setLocationRelativeTo(null);
            frame[0].setVisible(true);
        });
        try {
            run(gc, frame[0]);
        } finally {
            SwingUtilities.invokeAndWait(() -> frame[0].dispose());
        }
    }

    private static void run(GraphicsConfiguration gc, JFrame frame) throws Exception {
        VolatileImage[] images = new VolatileImage[SURFACES];
        for (int i = 0; i < SURFACES; i++) {
            images[i] = gc.createCompatibleVolatileImage(W, H);
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 14);

        // warm up: fill the glyph cache and create all surfaces
        for (int i = 0; i < SURFACES; i++) {
            drawText(images[i], font, i);
        }
        BufferedImage reference = images[0].getSnapshot();

        long start = System.nanoTime();
        int switches = 0;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            for (int i = 0; i < SURFACES; i++) {
                drawText(images[i], font, i);
                switches++;
            }
            // one on-screen blit per iteration, as Swing does after a repaint
            Graphics2D g = (Graphics2D) frame.getGraphics();
            if (g != null) {
                g.drawImage(images[iter % SURFACES], 20, H, null);
                g.dispose();
                switches++;
            }
        }
        frame.getToolkit().sync();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double msPerSwitch = (double) elapsedMs / switches;
        System.out.printf("%d surface switches in %d ms: %.3f ms per switch%n",
                          switches, elapsedMs, msPerSwitch);

        // the cached glyphs must produce the same pixels after many switches
        BufferedImage last = images[0].getSnapshot();
        int diff = countDifferentPixels(reference, last);
        if (diff != 0) {
            throw new RuntimeException("Text changed after surface switches: "
                                       + diff + " pixels differ");
        }
        for (VolatileImage image : images) {
            if (image.contentsLost()) {
                throw new RuntimeException("Volatile image contents were lost");
            }
        }
        if (msPerSwitch > MAX_MS_PER_SWITCH) {
            throw new RuntimeException("A surface switch is too slow: " + msPerSwitch
                                       + " ms, limit " + MAX_MS_PER_SWITCH + " ms");
        }
        System.out.println("PASSED");
    }

    private static void drawText(VolatileImage image, Font font, int index) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            // a different string per surface keeps the glyph cache busy
            g.drawString(TEXT.substring(index % 7), 4, 26);
        } finally {
            g.dispose();
        }
    }

    private static int countDifferentPixels(BufferedImage a, BufferedImage b) {
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
