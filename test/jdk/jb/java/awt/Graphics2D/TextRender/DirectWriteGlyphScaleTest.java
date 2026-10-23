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
 * @summary LCD glyphs from the DirectWrite glyph path (directwrite.font.rendering)
 *          have the size the strike asked for. The path draws into a GDI-interop
 *          bitmap render target whose pixels-per-DIP defaults to the display's
 *          scale factor, so on a scaled display (150 %, 200 %) every glyph came
 *          out that factor too large and overlapped its neighbours; the strike
 *          already passes device pixels as the em size. The capital H of an
 *          installed font at 13 px is rendered through the path and its ink
 *          height is compared with the cap height the scaler reports: within
 *          one pixel. Red on a scaled display before the fix, green on a 100 %
 *          display regardless.
 * @requires (os.family == "windows")
 * @run main/othervm -Ddirectwrite.font.rendering=true -Dsun.java2d.uiScale=1 DirectWriteGlyphScaleTest
 * @run main/othervm -Dsun.java2d.uiScale=1 DirectWriteGlyphScaleTest
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

public class DirectWriteGlyphScaleTest {

    public static void main(String[] args) {
        String path = Boolean.getBoolean("directwrite.font.rendering") ? "DirectWrite" : "GDI";
        StringBuilder failures = new StringBuilder();
        for (String family : new String[] {"Segoe UI", "Consolas", "Arial"}) {
            Font font = new Font(family, Font.PLAIN, 13);
            if (!font.getFamily().equalsIgnoreCase(family)) {
                System.out.println(family + " not installed, skipped");
                continue;
            }
            // the outline's cap height from the scaler: the size the glyph image must have
            FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
                                                          RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            GlyphVector gv = font.createGlyphVector(frc, "H");
            Rectangle2D outline = gv.getOutline().getBounds2D();
            BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setColor(Color.BLACK);
                g.setFont(font);
                g.drawString("HHH", 4, 28);
            } finally {
                g.dispose();
            }
            int top = Integer.MAX_VALUE, bottom = -1, left = Integer.MAX_VALUE, right = -1;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int p = image.getRGB(x, y);
                    if (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255) < 3 * 128) {   // more than half covered
                        top = Math.min(top, y); bottom = Math.max(bottom, y); left = Math.min(left, x); right = Math.max(right, x);
                    }
                }
            }
            int inkHeight = bottom - top + 1, inkWidth = right - left + 1;
            double expectedHeight = outline.getHeight();
            double expectedWidth = gv.getGlyphMetrics(0).getAdvanceX() * 2 + outline.getWidth();
            System.out.printf("%s via %s: ink %dx%d px, outline cap height %.1f, three advances %.1f%n",
                              family, path, inkWidth, inkHeight, expectedHeight, expectedWidth);
            if (Math.abs(inkHeight - expectedHeight) > 1.5 || Math.abs(inkWidth - expectedWidth) > 2.5) {
                failures.append(family).append(" via ").append(path).append(": ink ").append(inkWidth).append("x").append(inkHeight)
                        .append(" for expected ").append(String.format("%.1fx%.1f", expectedWidth, expectedHeight)).append("; ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Glyph images do not have the strike's size: " + failures);
        }
        System.out.println("PASSED");
    }
}
