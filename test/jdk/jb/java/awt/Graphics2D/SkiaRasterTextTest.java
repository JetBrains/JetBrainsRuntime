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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary With sun.java2d.skia.raster=true and sun.java2d.skia.text=skia on the
 *          Skia font scaler, text on a BufferedImage goes through Skia's own text
 *          path (the strike's SkFont, Skia's glyph masks and blitters) instead of
 *          the software loops' glyph images: strings, chars and glyph vectors in
 *          grayscale and LCD antialiasing land where the loops put them with a
 *          comparable amount of ink (Skia's gamma model lightens plain coverage by
 *          up to a quarter), LCD text carries colour fringes in the loops' sub-pixel
 *          order where the platform renders sub-pixel masks (not macOS), and the
 *          pipe counts the glyphs it drew. A composite strike — a glyph vector's on
 *          macOS, a logical or Swing UI font's elsewhere — is drawn slot by slot
 *          through each physical strike's SkFont, so every case counts its glyphs. The cg grayscale route
 *          (macOS: CoreGraphics' smoothed masks by text luminance) is taken on the
 *          raster surface too, matching the loops' ink within 15 %.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia SkiaRasterTextTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia -Dsun.java2d.skia.text.gray=cg SkiaRasterTextTest cg
 */
public class SkiaRasterTextTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 420, H = 48;
    static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    public static void main(String[] args) throws Exception {
        boolean cg = args.length > 0 && args[0].equals("cg");
        if (cg && !System.getProperty("os.name", "").startsWith("Mac")) {
            System.out.println("the cg route is CoreGraphics': nothing to test here");
            return;
        }
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        double low = cg ? 0.85 : 0.70, high = cg ? 1.15 : 1.02;
        check("drawString, grayscale AA", g -> g.drawString(TEXT, 8, 30), font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, low, high);
        check("drawChars, grayscale AA", g -> g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), 8, 30), font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, low, high);
        check("drawGlyphVector, grayscale AA", g -> { GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), TEXT); g.drawGlyphVector(gv, 8, 30); }, font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, low, high);
        check("drawString, translucent colour", g -> { g.setColor(new Color(20, 40, 160, 128)); g.drawString(TEXT, 8, 30); }, font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, low, high);
        if (!cg) {
            // macOS: CoreGraphics renders no sub-pixel masks since Mojave, so Skia's CoreText host draws
            // LCD-hinted text as grayscale (the loops blend the JDK's neutral "LCD" masks with their
            // gamma, darker); elsewhere Skia's LCD masks must come out in the loops' sub-pixel order
            check("drawString, LCD HRGB", g -> g.drawString(TEXT, 8, 30), font, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, MAC ? 0.55 : 0.6, MAC ? 1.05 : 1.2);
        }
        long bySkia = glyphsBySkia();
        if (bySkia == 0) {
            throw new RuntimeException("no text reached Skia's raster text path (the counter is 0)");
        }
        System.out.println("PASSED (" + bySkia + " glyphs by Skia)");
    }

    static long glyphsBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterGlyphsBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Draws with Skia (INT_ARGB_PRE) and the loops (3BYTE_BGR): same ink bounds, ink ratio within [low, high]; LCD: fringes in the loops' order. */
    static void check(String what, Consumer<Graphics2D> drawing, Font font, Object aa, double low, double high) {
        long before = glyphsBySkia();
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            g.setColor(Color.BLACK); g.setFont(font);
            drawing.accept(g);
            g.dispose();
        }
        Rectangle sb = inkBounds(skia), lb = inkBounds(loops);
        double si = ink(skia), li = ink(loops), ratio = si / li;
        long fringeAgreement = 0; int fringes = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int s = skia.getRGB(x, y), l = loops.getRGB(x, y);
            int ds = ((s >> 16) & 255) - (s & 255), dl = ((l >> 16) & 255) - (l & 255);
            if (ds != 0) fringes++;
            fringeAgreement += (long) ds * dl;
        }
        long drawn = glyphsBySkia() - before;
        System.out.printf("%s: %d glyphs by Skia; ink bounds Skia %s, loops %s; ink ratio %.3f; fringe pixels %d, order agreement %d%n", what, drawn, sb, lb, ratio, fringes, fringeAgreement);
        if (drawn < TEXT.replace(" ", "").length()) {
            throw new RuntimeException(what + ": only " + drawn + " glyphs went through Skia's raster text path (a composite strike — a glyph vector's, or a logical font's — must be drawn slot by slot)");
        }
        if (Math.abs(sb.x - lb.x) > 1 || Math.abs(sb.y - lb.y) > 1 || Math.abs(sb.width - lb.width) > 2 || Math.abs(sb.height - lb.height) > 2) {
            throw new RuntimeException(what + ": the text is not where the loops put it: " + sb + " vs " + lb);
        }
        if (ratio < low || ratio > high) {
            throw new RuntimeException(what + ": ink ratio " + ratio + " outside [" + low + ", " + high + "]");
        }
        if (aa == RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB) {
            if (MAC) {
                if (fringes >= 100) {
                    throw new RuntimeException(what + ": colour fringes on macOS, where CoreGraphics renders no sub-pixel masks (" + fringes + " pixels)");
                }
                return;
            }
            if (fringes < 100) {
                throw new RuntimeException(what + ": no LCD colour fringes (" + fringes + " pixels with R != B)");
            }
            if (fringeAgreement <= 0) {
                throw new RuntimeException(what + ": the sub-pixel order is reversed against the loops (agreement " + fringeAgreement + ")");
            }
        }
    }

    static Rectangle inkBounds(BufferedImage img) {
        int x1 = W, y1 = H, x2 = -1, y2 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((img.getRGB(x, y) & 0xffffff) != 0xffffff) { x1 = Math.min(x1, x); y1 = Math.min(y1, y); x2 = Math.max(x2, x); y2 = Math.max(y2, y); }
        return x2 < 0 ? new Rectangle() : new Rectangle(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
    }

    static double ink(BufferedImage img) {
        double sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int p = img.getRGB(x, y); sum += 765 - ((p >> 16) & 255) - ((p >> 8) & 255) - (p & 255); }
        return sum;
    }
}
