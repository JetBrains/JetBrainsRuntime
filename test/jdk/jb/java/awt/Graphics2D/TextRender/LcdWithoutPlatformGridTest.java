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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary On a platform whose rasterizer defines no placement grid for LCD text
 *          (macOS: CoreGraphics draws no colour sub-pixels, the cg route supplies
 *          grayscale masks only), LCD glyphs under the cg route keep the JDK's own
 *          LCD rendering: sub-pixel colour fringes from the scaler's LCD triplets
 *          and the channel shift by thirds, not grayscale variants. The same text
 *          drawn LCD with fractional metrics must show colour fringes.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg LcdWithoutPlatformGridTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia LcdWithoutPlatformGridTest
 */
public class LcdWithoutPlatformGridTest {
    public static void main(String[] args) {
        int w = 400, h = 40;
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 13));
        g.drawString("Hamburgefonts Quick fox jumps 1234567890", 4, 26);
        g.dispose();
        int fringe = 0, ink = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = im.getRGB(x, y);
            int r = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
            if (r != 255 || gr != 255 || b != 255) ink++;
            if (Math.max(r, Math.max(gr, b)) - Math.min(r, Math.min(gr, b)) > 48) fringe++;
        }
        System.out.println("ink pixels " + ink + ", colour-fringe pixels " + fringe);
        if (ink < 500) {
            throw new RuntimeException("no text drawn: " + ink + " ink pixels");
        }
        if (fringe < ink / 10) {
            throw new RuntimeException("LCD text came out without colour fringes (grayscale variants?): " + fringe + " of " + ink + " ink pixels");
        }
    }
}
