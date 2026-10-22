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
 * @summary With -Dsun.java2d.metal.textGammaCorrection=false the Metal pipeline
 *          blends grayscale text like the software loops: the same glyph images
 *          drawn into a VolatileImage and into a BufferedImage differ by at most
 *          a few levels, black on white and white on black alike. By default
 *          the Metal shader raises the coverage to an exponent that depends on
 *          the text brightness (JBR-3820), which makes black text about 12 %
 *          lighter and white text about 18 % heavier than the software loops.
 * @requires os.family == "mac"
 * @key headful
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=true -Dsun.java2d.metal.textGammaCorrection=false MetalTextPlainBlendTest
 */
public class MetalTextPlainBlendTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 400, H = 40;

    public static void main(String[] args) {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not the Metal pipeline: " + gc.getClass().getName());
        }
        Font font = new Font("Helvetica Neue", Font.PLAIN, 13);
        for (boolean light : new boolean[] {false, true}) {
            Color ink = light ? Color.WHITE : Color.BLACK, paper = light ? Color.BLACK : Color.WHITE;
            BufferedImage software = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(software.createGraphics(), font, ink, paper);
            BufferedImage metal;
            VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            do {
                vi.validate(gc);
                paint(vi.createGraphics(), font, ink, paper);
                metal = vi.getSnapshot();
            } while (vi.contentsLost());
            long inkSoftware = 0, inkMetal = 0;
            int max = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int s = software.getRGB(x, y), m = metal.getRGB(x, y), p = paper.getRGB();
                    for (int sh = 0; sh < 24; sh += 8) {
                        int cs = (s >> sh) & 255, cm = (m >> sh) & 255, cp = (p >> sh) & 255;
                        inkSoftware += Math.abs(cs - cp);
                        inkMetal += Math.abs(cm - cp);
                        max = Math.max(max, Math.abs(cs - cm));
                    }
                }
            }
            double ratio = (double) inkMetal / inkSoftware;
            System.out.println((light ? "white" : "black") + " text: software ink " + inkSoftware + ", Metal ink " + inkMetal
                               + ", ratio " + ratio + ", max difference " + max);
            if (max > 4 || ratio < 0.99 || ratio > 1.01) {
                throw new RuntimeException("Metal does not blend grayscale text like the software loops: ratio " + ratio + ", max difference " + max);
            }
        }
    }

    static void paint(Graphics2D g, Font font, Color ink, Color paper) {
        g.setColor(paper);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(ink);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
    }
}
