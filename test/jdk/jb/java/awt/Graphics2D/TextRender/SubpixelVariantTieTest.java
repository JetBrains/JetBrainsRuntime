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

/*
 * @test
 * @key headful
 * @summary The pipes pick the sub-pixel variant of a glyph by rounding the
 *          fractional device position to the nearest of the strike's steps.
 *          A position exactly halfway between two steps must round up, the
 *          way DirectWrite does (measured: its grid boundaries sit exactly on
 *          the half-steps and the half-step takes the upper position), and
 *          both pipes must agree. The pipes' arithmetic (coord + 0.5/res - 0.5,
 *          then truncation) decides such ties by float rounding noise: with
 *          six ClearType steps at a display scale of 1.25 the software loops
 *          took a position of x.25 down to x.1667 while the accelerated pipe
 *          took it up to x.3333; monospaced text at 125 % puts every fourth
 *          glyph on such a tie. A glyph drawn at a tie must render exactly like
 *          the glyph drawn at the upper step, through a BufferedImage (the
 *          software loops) and a VolatileImage (the accelerated pipe), for
 *          grayscale (4 steps at 1x) and LCD (6 steps at 1x and 1.25x).
 *          On macOS only the grayscale case applies (CoreGraphics draws no colour
 *          sub-pixels, so LCD glyphs there have no variants): the platform scaler on
 *          Metal and the software loops, and the Skia scaler on the Skia pipe.
 * @requires (os.family == "windows") | (os.family == "mac")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform SubpixelVariantTieTest
 * @run main/othervm -Dsun.java2d.uiScale=1 SubpixelVariantTieTest gray
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia SubpixelVariantTieTest gray
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
import java.util.Arrays;

public class SubpixelVariantTieTest {

    public static void main(String[] args) {
        Font font = new Font("Inter", Font.PLAIN, 13);
        if (!font.getFamily().equalsIgnoreCase("Inter")) {
            font = new Font("Segoe UI", Font.PLAIN, 13);
        }
        StringBuilder failures = new StringBuilder();
        // {lcd, scale, steps}: the ties are at (k + 0.5) / steps device pixels
        boolean grayOnly = args.length > 0 && args[0].equals("gray");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
        if (!grayOnly && !windows) {
            System.out.println("the LCD cases need the platform's LCD variants (Windows); skipped");
            return;
        }
        Object[][] cases = grayOnly ? new Object[][] {{false, 1.0, 4}} : new Object[][] {{false, 1.0, 4}, {true, 1.0, 6}, {true, 1.25, 6}};
        for (boolean accelerated : new boolean[] {false, true}) {
            String pipe = accelerated ? "accelerated pipe" : "software loops";
            for (Object[] c : cases) {
                boolean lcd = (Boolean) c[0];
                double scale = (Double) c[1];
                int steps = (Integer) c[2];
                for (int k = 0; k < steps; k++) {
                    double tie = (k + 0.5) / steps, upper = (k + 1.0) / steps, lower = (double) k / steps;
                    int[] atTie = render(font, accelerated, lcd, scale, 8 + tie);
                    int[] atUpper = render(font, accelerated, lcd, scale, 8 + upper);
                    int[] atLower = render(font, accelerated, lcd, scale, 8 + lower);
                    String what = String.format("%s, %s at %sx, tie %.4f", pipe, lcd ? "LCD" : "gray", scale, tie);
                    if (Arrays.equals(atTie, atUpper)) {
                        System.out.println(what + ": rounds up (ok)");
                    } else if (Arrays.equals(atTie, atLower)) {
                        System.out.println(what + ": rounds DOWN");
                        failures.append(what).append(" rounds down; ");
                    } else {
                        System.out.println(what + ": matches neither neighbour");
                        failures.append(what).append(" matches neither neighbour; ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Sub-pixel variant ties: " + failures);
        }
        System.out.println("PASSED");
    }

    /** The pixels of the glyph 'l' drawn black on white at the device x, through the chosen pipe. */
    static int[] render(Font font, boolean accelerated, boolean lcd, double scale, double deviceX) {
        int w = (int) (40 * scale), h = (int) (30 * scale);
        if (!accelerated) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            paint(image.createGraphics(), font, lcd, scale, deviceX, w, h);
            return image.getRGB(0, 0, w, h, null, 0, w);
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
        BufferedImage snapshot;
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font, lcd, scale, deviceX, w, h);
            snapshot = vi.getSnapshot();
        } while (vi.contentsLost());
        return snapshot.getRGB(0, 0, w, h, null, 0, w);
    }

    static void paint(Graphics2D g, Font font, boolean lcd, double scale, double deviceX, int w, int h) {
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            g.drawString("l", (float) (deviceX / scale), (float) (20 / scale));
        } finally {
            g.dispose();
        }
    }
}
