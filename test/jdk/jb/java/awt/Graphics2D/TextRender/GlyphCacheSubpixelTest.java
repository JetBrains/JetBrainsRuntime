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
 * @summary With subpixel glyph positioning a glyph owns one cache cell per
 *          subimage, chained in the glyph's cell list. Evicting one of them
 *          must detach exactly that cell: text at four fractional x offsets
 *          must render identically before and after the cache was filled
 *          with other glyphs several times over.
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.metal=true -Djava2d.font.subpixelResolution=4x1 GlyphCacheSubpixelTest
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

public class GlyphCacheSubpixelTest {

    private static final int W = 900;
    private static final int H = 120;
    private static final float[] OFFSETS = {10.0f, 10.25f, 10.5f, 10.75f};
    private static final String TEXT = "Subpixel glyph positions survive eviction 0123456789";

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            System.out.println("The Metal pipeline is not active, nothing to test: " + gc.getClass());
            return;
        }
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);

        BufferedImage[] reference = new BufferedImage[OFFSETS.length];
        for (int i = 0; i < OFFSETS.length; i++) {
            drawAt(image, font, OFFSETS[i]);
            reference[i] = image.getSnapshot();
        }
        int distinctOffsets = 0;
        for (int i = 1; i < OFFSETS.length; i++) {
            if (countDifferentPixels(reference[0], reference[i]) != 0) {
                distinctOffsets++;
            }
        }
        System.out.println(distinctOffsets + " of " + (OFFSETS.length - 1)
                           + " fractional offsets render differently from offset 0");

        // fill the caches several times over with other glyphs
        VolatileImage churn = gc.createCompatibleVolatileImage(1400, 1000, Transparency.OPAQUE);
        StringBuilder sb = new StringBuilder();
        for (char c = 33; c < 127; c++) {
            sb.append(c);
        }
        String alphabet = sb.toString();
        for (int frame = 0; frame < 30; frame++) {
            Graphics2D g = churn.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 1400, 1000);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                int y = 20;
                for (int i = 0; i < 8; i++) {
                    Font f = new Font(Font.SANS_SERIF, i % 2 == 0 ? Font.PLAIN : Font.BOLD, 8 + (frame * 8 + i) % 32);
                    g.setFont(f);
                    g.drawString(alphabet, 10.3f, y);   // fractional too: several subimages per glyph
                    y += f.getSize() + 6;
                }
            } finally {
                g.dispose();
            }
        }

        for (int i = 0; i < OFFSETS.length; i++) {
            drawAt(image, font, OFFSETS[i]);
            int diff = countDifferentPixels(reference[i], image.getSnapshot());
            if (diff != 0) {
                throw new RuntimeException("Text at x=" + OFFSETS[i] + " changed after eviction: "
                                           + diff + " pixels differ");
            }
        }
        if (image.contentsLost() || churn.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        System.out.println("PASSED");
    }

    private static void drawAt(VolatileImage image, Font font, float x) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(TEXT, x, 40f);
            g.drawString(TEXT, x + 0.5f, 80f);
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
