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
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * @test
 * @key headful
 * @summary Without fractional metrics Java rounds a system font's advances in user space (CoreText's
 *          rule: whole points, so whole multiples of the scale in device pixels), and Skia's own
 *          layouts — the shaper (text.shaping=skia) and the paragraph engine (text.paragraphs=skia) —
 *          must place the glyphs where Java's layout does at every display scale, not at Skia's
 *          device-pixel rounding (at 2x the two drifted apart by a device pixel per few glyphs,
 *          three points over a short line; 2026-11-03). Each glyph's first ink column on a
 *          scaled surface is compared against a BufferedImage rendering through Java's layout.
 * @requires (os.family == "mac" | os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.shaping=skia SkiaWholePixelAdvancesTest plain
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia SkiaWholePixelAdvancesTest attributed
 */
public class SkiaWholePixelAdvancesTest {
    static final int W = 320, H = 40, BASELINE = 24;
    static final String TEXT = "Quick fox jumps 1234567890 fifty";

    public static void main(String[] args) throws Exception {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        boolean attributed = args[0].equals("attributed");
        Font font = new Font(mac ? "Helvetica Neue" : "Segoe UI", Font.PLAIN, 13);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        List<String> errors = new ArrayList<>();
        for (double scale : new double[] {1, 2, 1.5}) {
            BufferedImage reference = new BufferedImage((int) (W * scale), (int) (H * scale), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = reference.createGraphics();
            draw(g, font, scale, attributed);
            g.dispose();
            BufferedImage skia = onScreen(gc, font, scale, attributed);
            List<Integer> refStarts = glyphStarts(reference, scale), skiaStarts = glyphStarts(skia, scale);
            System.out.printf("%sx: Java's layout %s%n     Skia's        %s%n", scale, refStarts, skiaStarts);
            if (!refStarts.equals(skiaStarts)) {
                errors.add(scale + "x: the glyphs start at " + skiaStarts + ", Java's layout puts them at " + refStarts);
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    static void draw(Graphics2D g, Font font, double scale, boolean attributed) {
        g.setColor(Color.WHITE); g.fillRect(0, 0, (int) (W * scale), (int) (H * scale));
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        if (attributed) {
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            as.addAttribute(TextAttribute.FOREGROUND, Color.BLUE, 6, 9);   // two runs: the paragraph engine's route
            g.drawString(as.getIterator(), 4, BASELINE);
        } else {
            g.drawString(TEXT, 4, BASELINE);
        }
    }

    static BufferedImage onScreen(GraphicsConfiguration gc, Font font, double scale, boolean attributed) {
        VolatileImage vi = gc.createCompatibleVolatileImage((int) (W * scale), (int) (H * scale), Transparency.OPAQUE);
        BufferedImage img;
        do {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            draw(g, font, scale, attributed);
            g.dispose();
            img = vi.getSnapshot();
        } while (vi.contentsLost());
        return img;
    }

    /** The first column of every ink run on the x-height rows: where each glyph (or touching pair) starts. */
    static List<Integer> glyphStarts(BufferedImage img, double scale) {
        int y0 = (int) ((BASELINE - 6) * scale), y1 = (int) ((BASELINE - 1) * scale);
        List<Integer> starts = new ArrayList<>();
        boolean prev = false;
        for (int x = 0; x < img.getWidth(); x++) {
            boolean ink = false;
            for (int y = y0; y <= y1 && !ink; y++) ink = (img.getRGB(x, y) & 0xff) < 128;
            if (ink && !prev) starts.add(x);
            prev = ink;
        }
        return starts;
    }
}
