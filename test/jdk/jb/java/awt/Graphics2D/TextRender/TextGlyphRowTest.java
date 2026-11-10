/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;

/**
 * @test
 * @summary On macOS a horizontal line's glyphs go to the device row below a fractional baseline, as CoreText places
 *          them: CTLineDraw, NSAttributedString string drawing and TextKit 2 alike put the glyphs' bottom edge on the
 *          row under the baseline for any fraction past 1/1024 of a point, at 1x and 2x (measured 2026-11-10 in an
 *          NSView, aktk2revalidate.swift glyphrow: 0.001 pt moves them, 0.0005 pt does not). Java2D rounded the
 *          baseline to the nearest row on every route, a row above CoreText's for fractions under one half (ledger
 *          §18a T22). Every text entry point (drawString, drawChars through the float form, drawGlyphVector,
 *          TextLayout.draw, an attributed string), at 13 pt and as outlines at 150 pt (CoreText places large glyphs
 *          alike), at 1x and under a 2x scale: the loops with the JDK and the Skia
 *          scaler, the raster surface with Skia's text path and its layout route, and the GPU pipes.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true TextGlyphRowTest image
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia TextGlyphRowTest image
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TextGlyphRowTest image
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextGlyphRowTest image
 */

/*
 * @test id=gpu
 * @summary The same on the Metal pipeline and the Skia GPU pipe (Java's glyph images, Skia's text path, its layout route).
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 TextGlyphRowTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true TextGlyphRowTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TextGlyphRowTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextGlyphRowTest gpu
 */
public class TextGlyphRowTest {
    static final String TEXT = "xxx";   // flat bottoms on the baseline
    static final double[] FRACTIONS = {0, 0.0005, 0.002, 0.125, 0.25, 0.375, 0.5, 0.75, 0.875};   // of a point

    public static void main(String[] args) {
        boolean gpu = args[0].equals("gpu");
        String[] names = {"drawString", "drawGlyphVector", "TextLayout.draw", "attributed string"};
        StringBuilder failures = new StringBuilder();
        // 13 pt from glyph images; 150 pt as outlines (above OutlineTextRenderer's threshold; Skia draws its own), placed alike
        for (int size : new int[] {13, 150}) for (int scale = 1; scale <= 2; scale++) {
            Font font = new Font("Helvetica Neue", Font.PLAIN, size);
            for (int e = 0; e < names.length; e++) {
                for (double f : FRACTIONS) {
                    int expected = (int) Math.ceil((f - 1.0 / 1024) * scale);   // the row below the device baseline, from 100's row
                    double seen = bottom(gpu, font, e, scale, 100 + f);
                    String what = names[e] + " " + size + " pt at " + scale + "x, baseline 100 + " + f;
                    System.out.println(what + ": glyph bottom " + seen + " (CoreText " + expected + ")");
                    if (Math.abs(seen - expected) > 0.05) {
                        failures.append(what).append(": the glyphs' bottom at ").append(seen).append(", CoreText's at ").append(expected).append("; ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    /** The glyphs' bottom edge in device rows below the baseline's whole row (the last ink row plus its coverage). */
    static double bottom(boolean gpu, Font font, int entry, int scale, double y) {
        int w = (40 + 3 * font.getSize()) * scale, h = 140 * scale;
        VolatileImage vi = null;
        BufferedImage image = null;
        Graphics2D g;
        if (gpu) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(w, h);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            g = image.createGraphics();
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(scale, scale);
        g.setFont(font);
        float fy = (float) y;
        switch (entry) {
            case 0 -> g.drawString(TEXT, 10f, fy);
            case 1 -> g.drawGlyphVector(font.createGlyphVector(g.getFontRenderContext(), TEXT), 10f, fy);
            case 2 -> new TextLayout(TEXT, font, g.getFontRenderContext()).draw(g, 10f, fy);
            default -> {
                AttributedString as = new AttributedString(TEXT);
                as.addAttribute(TextAttribute.FONT, font);
                g.drawString(as.getIterator(), 10f, fy);
            }
        }
        g.dispose();
        BufferedImage im = vi != null ? vi.getSnapshot() : image;
        double[] rows = new double[h];
        for (int yy = 0; yy < h; yy++) for (int x = 0; x < w; x++) rows[yy] += (255 - (im.getRGB(x, yy) >> 8 & 0xff)) / 255.0;
        int last = 0;
        for (int yy = 2; yy < h; yy++) if (rows[yy] > 0.05 * scale) last = yy;
        return last + Math.min(1, rows[last] / Math.max(rows[last - 2], 1e-9)) - 100 * scale;
    }
}
