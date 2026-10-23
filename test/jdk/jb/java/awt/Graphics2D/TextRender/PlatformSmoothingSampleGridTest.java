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
 * @summary The Skia scaler's platform-smoothing route on Windows renders a
 *          glyph the way Direct2D renders it on the display the strike is for.
 *          Direct2D's grayscale antialiasing in DirectWrite's natural-symmetric
 *          mode samples the outline on a 4 x 4 grid per device-independent
 *          pixel: at 100 % an edge pixel takes one of 16 coverage steps, on a
 *          200 % display (a device scale of 2, the way Java applies the
 *          display scale) a device pixel holds 2 x 2 samples and an edge
 *          pixel one of 4 steps (gray 0, 71, 137, 195, 255 for black on white
 *          with the default rendering parameters; measured with a 192-dpi
 *          Direct2D target). A route that draws the device pixel size at
 *          96 dpi gets the 16 steps at every scale and does not look like the
 *          platform at 200 %. Black text on white, drawn at a device scale of
 *          1 and of 2: the number of distinct gray levels must drop to the
 *          platform's.
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform PlatformSmoothingSampleGridTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.TreeSet;

public class PlatformSmoothingSampleGridTest {

    static final String TEXT = "Quick fox jumps over the lazy dog";

    public static void main(String[] args) {
        StringBuilder failures = new StringBuilder();
        for (Font font : new Font[] {new Font("Segoe UI", Font.PLAIN, 13), new Font("Inter", Font.PLAIN, 13)}) {
            if (!font.getFamily().equalsIgnoreCase(font.getName())) {
                System.out.println(font.getName() + " not available, skipped");
                continue;
            }
            TreeSet<Integer> at1 = levels(font, 1), at15 = levels(font, 1.5), at2 = levels(font, 2);
            System.out.println(font.getName() + " at 1x: " + at1.size() + " gray levels " + at1);
            System.out.println(font.getName() + " at 1.5x: " + at15.size() + " gray levels " + at15);
            System.out.println(font.getName() + " at 2x: " + at2.size() + " gray levels " + at2);
            // the coarse regime starts at a display scale of 1.8 (measured between 1.79 and 1.81): at
            // 1.5 the sampling is still the fine one
            if (at15.size() < 9) {
                failures.append(font.getName()).append(" at 1.5x: ").append(at15.size()).append(" gray levels, Direct2D keeps its fine sampling below a scale of 1.8; ");
            }
            // at 1x 16 steps in the symmetric mode (Inter), 8 in the natural mode (Segoe UI, hinted
            // TrueType at text sizes), plus the two ends; at 2x the platform's 4 steps plus the ends,
            // with a little room for a level the blend rounds apart
            if (at1.size() < 9) {
                failures.append(font.getName()).append(" at 1x: ").append(at1.size()).append(" gray levels, Direct2D has 8 or 16 steps per pixel at 100 %; ");
            }
            if (at2.size() > 7) {
                failures.append(font.getName()).append(" at 2x: ").append(at2.size()).append(" gray levels, Direct2D has 4 steps per device pixel at 200 %; ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Platform smoothing sample grid: " + failures);
        }
        System.out.println("PASSED");
    }

    /** The distinct gray values of the line drawn black on white at the device scale. */
    static TreeSet<Integer> levels(Font font, double scale) {
        int w = (int) (260 * scale), h = (int) (24 * scale);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            g.drawString(TEXT, 4, 17);
        } finally {
            g.dispose();
        }
        TreeSet<Integer> levels = new TreeSet<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                levels.add(image.getRGB(x, y) & 255);
            }
        }
        return levels;
    }
}
