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
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * @test
 * @summary A glyph drawn in glyph lists of different cache buckets renders the same
 *          every time. A Metal glyph list takes the bucket of its largest glyph, so the
 *          same 'l' is drawn from the 32-pixel cache in "l" and from the 64-pixel cache
 *          in "lW" (the 'W' is wider than 32 pixels at this size); a cell found by its
 *          sub-image alone, without checking which cache it belongs to, draws the glyph
 *          with another texture's coordinates and produces garbage or nothing (seen as
 *          missing and garbled glyphs in IDE captures, 2026-10-26).
 * @requires os.family == "mac"
 * @key headful
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=true MetalGlyphCacheMixedBucketsTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true MetalGlyphCacheMixedBucketsTest
 */
public class MetalGlyphCacheMixedBucketsTest {
    static final int W = 300, H = 120;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        Font font = new Font("Helvetica Neue", Font.PLAIN, 36);   // 'l' is about 28 px tall (a 32-pixel cell), 'W' about 34 px wide (the 64-pixel bucket)
        for (boolean fractional : new boolean[]{true, false}) {   // sub-pixel variants, and the single-image path
            VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            BufferedImage first, lone, again;
            do {
                vi.validate(gc);
                paint(vi, font, "lW", fractional); first = vi.getSnapshot();    // 'l' and 'W' cached in the 64-pixel bucket
                paint(vi, font, "l", fractional);  lone = vi.getSnapshot();     // a list of the 32-pixel bucket: 'l' must be cached and drawn there
                paint(vi, font, "lW", fractional); again = vi.getSnapshot();    // back to the 64-pixel bucket
            } while (vi.contentsLost());
            BufferedImage ref = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(ref.createGraphics(), font, "l", fractional);
            int diffAgain = differing(first, again), diffLone = differing(lone, ref), inkLone = ink(lone), inkRef = ink(ref);
            System.out.println((fractional ? "fractional" : "whole-pixel") + ": 'lW' first vs again " + diffAgain + " px differ; lone 'l' vs software " + diffLone + " px differ, ink " + inkLone + " vs " + inkRef);
            if (diffAgain != 0) {
                throw new RuntimeException("'lW' renders differently after 'l' was drawn in another bucket (" + diffAgain + " pixels)");
            }
            if (inkLone < inkRef / 2 || diffLone > inkRef) {   // a wrong texture gives nothing or garbage; the pipes' blends differ in the fringes only
                throw new RuntimeException("the lone 'l' is not the glyph (ink " + inkLone + " vs " + inkRef + ", " + diffLone + " pixels differ from the software rendering)");
            }
        }
    }

    static int ink(BufferedImage im) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((im.getRGB(x, y) & 0xffffff) != 0xffffff) n++;
        return n;
    }

    static void paint(VolatileImage vi, Font font, String s, boolean fractional) {
        paint(vi.createGraphics(), font, s, fractional);
    }

    static void paint(Graphics2D g, Font font, String s, boolean fractional) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(s, 20, 80);
        g.dispose();
    }

    static int differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((a.getRGB(x, y) & 0xffffff) != (b.getRGB(x, y) & 0xffffff)) n++;
        return n;
    }
}
