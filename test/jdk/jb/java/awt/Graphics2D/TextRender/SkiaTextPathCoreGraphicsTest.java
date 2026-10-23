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
import java.lang.reflect.Method;

/**
 * @test
 * @summary With -Dsun.java2d.skia.text.gray=cg on Skia's own text path (text=skia, shaper
 *          or paragraph), the glyph masks inside Skia's strike come from CoreGraphics with
 *          font smoothing for the text colour's luminance, blended plainly, as the cg route
 *          does for the glyph-image pipes: the ink of black text on white and of light
 *          text on dark drawn by the Skia text path is within 2 % of the software pipe's
 *          with the same route for a system font and a file font, within 8 % for a CJK
 *          string the font lacks (the fallback runs take the route; the fallback font
 *          selection differs), and the glyphs were drawn by Skia's text path (counter).
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=shaper -Dsun.java2d.skia.text.gray=cg SkiaTextPathCoreGraphicsTest
 */
public class SkiaTextPathCoreGraphicsTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final String CJK = "日本語テキスト 中文文本 한국어";
    static final int W = 400, H = 40;
    static final Color DARK_INK = new Color(30, 30, 40), DARK_PAPER = new Color(30, 31, 34), LIGHT_INK = new Color(188, 190, 196);

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        for (Font font : new Font[] {new Font("Helvetica Neue", Font.PLAIN, 13), new Font("Inter", Font.PLAIN, 13)}) {
            if (!font.getFamily().equals(font.getName()) && !font.getFamily().startsWith("Inter") && !font.getFamily().startsWith("Helvetica")) {
                throw new RuntimeException("font not found: " + font.getName() + " resolved to " + font.getFamily());
            }
            check(gc, font, TEXT, DARK_INK, Color.WHITE, "dark on white", 0.02);
            check(gc, font, TEXT, LIGHT_INK, DARK_PAPER, "light on dark", 0.02);
        }
        // Characters the font lacks: the shaper's fallback runs take the route through the wrapping
        // font manager (Skia's plain masks would give ≈ 0.7). The residual (≈ 3.5 %) is the fallback
        // font selection: Skia's font manager and the JDK's CoreText call can pick different faces.
        check(gc, new Font("Helvetica Neue", Font.PLAIN, 13), CJK, DARK_INK, Color.WHITE, "CJK fallback, dark on white", 0.08);
    }

    static void check(GraphicsConfiguration gc, Font font, String text, Color ink, Color paper, String what, double tolerance) throws Exception {
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font, text, ink, paper);
        long before = drawnBySkia();
        BufferedImage skia = onScreen(gc, font, text, ink, paper);
        long drawn = drawnBySkia() - before;
        if (drawn < text.replace(" ", "").length()) {
            throw new RuntimeException(font.getName() + ", " + what + ": Skia's text path drew " + drawn + " glyphs, expected at least " + text.replace(" ", "").length());
        }
        long r = ink(reference, paper), s = ink(skia, paper);
        double ratio = (double) s / r;
        System.out.println(font.getName() + ", " + what + ": software cg ink " + r + ", Skia text path cg ink " + s + ", ratio " + ratio);
        if (Math.abs(ratio - 1) > tolerance) {
            throw new RuntimeException(font.getName() + ", " + what + ": the Skia text path does not draw the CoreGraphics masks: ink ratio " + ratio);
        }
    }

    static long drawnBySkia() throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsDrawnBySkia");
        return ((Number) m.invoke(null)).longValue();
    }

    static BufferedImage onScreen(GraphicsConfiguration gc, Font font, String text, Color ink, Color paper) {
        BufferedImage out;
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font, text, ink, paper);
            out = vi.getSnapshot();
        } while (vi.contentsLost());
        return out;
    }

    static void paint(Graphics2D g, Font font, String text, Color ink, Color paper) {
        g.setColor(paper);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(ink);
        g.setFont(font);
        g.drawString(text, 4, 26);
        g.dispose();
    }

    /** Σ|pixel − paper| over the image, summed over the channels. */
    static long ink(BufferedImage im, Color paper) {
        long sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = im.getRGB(x, y);
            sum += Math.abs(((p >> 16) & 255) - paper.getRed()) + Math.abs(((p >> 8) & 255) - paper.getGreen()) + Math.abs((p & 255) - paper.getBlue());
        }
        return sum;
    }
}
