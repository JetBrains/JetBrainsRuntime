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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.lang.reflect.Method;

/**
 * @test
 * @summary With -Dsun.java2d.skia.text=skia the Skia backend draws text through
 *          Skia's own text path (Skia's strike, glyph atlas and blending) instead
 *          of Java2D's glyph images: the backend counts the glyphs it drew that
 *          way, and the text lands where the glyph-image path puts it, with a
 *          comparable amount of ink (Skia applies its own gamma model, so the ink
 *          is not identical). Both strike kinds take the path: the system font's
 *          and the file font's. With sun.java2d.skia.text.smoothing=true the path
 *          asks the platform for smoothed glyphs (as Chrome does): black text on
 *          white then carries at least as much ink as the glyph-image path's
 *          plain coverage, instead of a fifth less.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia SkiaTextPathTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia -Dsun.java2d.skia.text.smoothing=true SkiaTextPathTest smoothing
 */
public class SkiaTextPathTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 400, H = 40;

    static boolean smoothing;

    public static void main(String[] args) throws Exception {
        smoothing = args.length > 0 && args[0].equals("smoothing");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        check(gc, new Font("Helvetica Neue", Font.PLAIN, 13), "system font");
        // a font loaded from a file has a different strike class behind the same scaler
        File file = new File(System.getProperty("java.home"), "lib/fonts/Inter-Regular.otf");
        check(gc, Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(13f), "file font");
    }

    static long glyphsDrawnBySkia() throws Exception {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsDrawnBySkia");
            return ((Number) m.invoke(null)).longValue();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("the backend has no Skia text path (no glyph counter)", e);
        }
    }

    static void check(GraphicsConfiguration gc, Font font, String what) throws Exception {
        long before = glyphsDrawnBySkia();
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font);
        BufferedImage skia;
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font);
            skia = vi.getSnapshot();
        } while (vi.contentsLost());

        long drawnBySkia = glyphsDrawnBySkia() - before;
        if (drawnBySkia < TEXT.replace(" ", "").length()) {
            throw new RuntimeException(what + ": Skia's text path drew " + drawnBySkia + " glyphs, expected at least " + TEXT.replace(" ", "").length());
        }
        Rectangle rb = inkBounds(reference), sb = inkBounds(skia);
        long ri = ink(reference), si = ink(skia);
        double ratio = (double) si / ri;
        System.out.println(what + ": reference ink " + ri + " bounds " + rb + "; Skia text path ink " + si + " bounds " + sb + ", ratio " + ratio + ", glyphs " + drawnBySkia);
        if (Math.abs(rb.x - sb.x) > 2 || Math.abs(rb.y - sb.y) > 2 || Math.abs(rb.width - sb.width) > 4 || Math.abs(rb.height - sb.height) > 4) {
            throw new RuntimeException("the text is not where the glyph-image path puts it: " + rb + " vs " + sb);
        }
        // measured: plain coverage ≈ 0.79 of the glyph-image path's ink, platform smoothing ≈ 1.0–1.05
        if (!smoothing && (ratio < 0.72 || ratio > 0.9)) {
            throw new RuntimeException(what + ": Skia's plain coverage should be 10–28 % lighter than the glyph images: ratio " + ratio);
        }
        if (smoothing && (ratio < 0.95 || ratio > 1.15)) {
            throw new RuntimeException(what + ": with platform smoothing requested the ink should match the glyph images within 15 %: ratio " + ratio);
        }
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
    }

    static Rectangle inkBounds(BufferedImage im) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((im.getRGB(x, y) & 0xffffff) != 0xffffff) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    static long ink(BufferedImage im) {
        long sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int c = im.getRGB(x, y); for (int s = 0; s < 24; s += 8) sum += 255 - ((c >> s) & 255); }
        return sum;
    }
}
