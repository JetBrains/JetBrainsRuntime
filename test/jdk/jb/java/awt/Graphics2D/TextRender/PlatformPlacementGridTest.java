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
 * @summary With the Skia scaler's DirectWrite routes, glyphs are placed on
 *          DirectWrite's own horizontal grid. Measured through the glyph run
 *          analysis and Direct2D alike: a face DirectWrite renders in the
 *          natural-symmetric mode (Inter, CFF outlines) takes 4 positions per
 *          pixel, a face it renders in the natural mode (Segoe UI, Consolas,
 *          instructed TrueType) takes 8, both rounded to the nearest step.
 *          Java renders sub-pixel variants of a glyph and rounds the position
 *          to the nearest one; with the JDK's fixed 4 variants the natural-mode
 *          faces landed up to 1/8 px off the platform's rendering (37 levels on
 *          stems). A single 'l' is drawn at 32 offsets across one pixel with
 *          fractional metrics; the number of distinct renderings is the grid.
 *          The grid is per device pixel at every display scale (a scale
 *          transform, as Java's HiDPI support applies it), but the symmetric
 *          mode has two regimes: quarter pixels up to a display scale of 1.79,
 *          half pixels from 1.81 on (measured 96 to 384 dpi), whatever the
 *          pixel size; the natural mode keeps its eighths at every scale.
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform PlatformPlacementGridTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platformCoverage PlatformPlacementGridTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

public class PlatformPlacementGridTest {

    public static void main(String[] args) throws Exception {
        StringBuilder failures = new StringBuilder();
        // At a device scale of 2 (a 200 % display, Java's scale transform) a 13 pt face is 26 px,
        // every face is in the symmetric mode, and the symmetric mode's high-scale regime has two
        // positions per device pixel. At 1.25 and 1.5 the grids are those of 1x: Segoe UI is still
        // in the natural mode at 16.25 px (eighths), and symmetric faces keep their quarters.
        Object[][] cases = {
            {new Font("Segoe UI", Font.PLAIN, 13), 8, "Segoe UI 13 (natural mode)", 1.0},
            {new Font("Consolas", Font.PLAIN, 12), 8, "Consolas 12 (natural mode)", 1.0},
            {interOrNull(), 4, "Inter 13 (natural-symmetric mode)", 1.0},
            {new Font("Segoe UI", Font.PLAIN, 13), 2, "Segoe UI 13 at 2x (symmetric mode, high-scale regime)", 2.0},
            {interOrNull(), 2, "Inter 13 at 2x (high-scale regime)", 2.0},
            {new Font("Segoe UI", Font.PLAIN, 13), 8, "Segoe UI 13 at 1.25x (natural mode)", 1.25},
            {interOrNull(), 4, "Inter 13 at 1.25x", 1.25},
            {interOrNull(), 4, "Inter 13 at 1.5x", 1.5},
            // ClearType: DirectWrite positions on sixths of a pixel (half a colour sub-pixel) in
            // both modes at every scale; the JDK's own LCD placement shifts the glyph image by
            // whole sub-pixels (thirds), which is 3 positions
            {new Font("Segoe UI", Font.PLAIN, 13), 6, "Segoe UI 13 LCD", 1.0, true},
            {interOrNull(), 6, "Inter 13 LCD", 1.0, true},
            {interOrNull(), 6, "Inter 13 LCD at 2x", 2.0, true},
        };
        for (Object[] c : cases) {
            Font font = (Font) c[0];
            if (font == null) {
                continue;
            }
            int expected = (Integer) c[1];
            String what = (String) c[2];
            double scale = (Double) c[3];
            boolean lcd = c.length > 4 && (Boolean) c[4];
            if (!font.getFamily().equalsIgnoreCase(font.getFontName().split(" ")[0]) && !font.getFamily().contains("Inter")
                && !font.getFamily().equalsIgnoreCase("Segoe UI") && !font.getFamily().equalsIgnoreCase("Consolas")) {
                System.out.println(what + ": font not available, skipped");
                continue;
            }
            Set<String> distinct = new HashSet<>();
            int w = (int) Math.ceil(24 * scale), h = (int) Math.ceil(28 * scale);
            for (int k = 0; k < 32; k++) {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, w, h);
                    g.scale(scale, scale);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                       lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    g.setColor(Color.BLACK);
                    g.setFont(font);
                    g.drawString("l", (float) ((4 + k / 32.0) / scale), (float) (20 / scale));   // k/32 device pixels
                } finally {
                    g.dispose();
                }
                StringBuilder key = new StringBuilder();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = image.getRGB(x, y);   // all three channels: LCD renderings differ per channel
                        key.append((char) ('0' + ((p >> 20) & 15))).append((char) ('0' + ((p >> 12) & 15))).append((char) ('0' + ((p >> 4) & 15)));
                    }
                }
                distinct.add(key.toString());
            }
            // the offsets cover [0, 1): the positions that round up to the next pixel give one more
            // rendering than the grid has steps (the glyph a whole pixel to the right)
            System.out.println(what + ": " + distinct.size() + " distinct renderings across one pixel, expected " + (expected + 1)
                               + " (" + expected + " grid steps and the wrap to the next pixel)");
            if (distinct.size() != expected + 1) {
                failures.append(what).append(": ").append(distinct.size() - 1).append(" positions per pixel, DirectWrite uses ").append(expected).append("; ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Placement grid: " + failures);
        }
        System.out.println("PASSED");
    }

    /** Inter from the runtime's font directory, the family the IDE uses; null if the image has none. */
    static Font interOrNull() {
        Font f = new Font("Inter", Font.PLAIN, 13);
        return f.getFamily().equalsIgnoreCase("Inter") ? f : null;
    }
}
