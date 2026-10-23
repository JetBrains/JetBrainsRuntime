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
import java.lang.reflect.Method;

/**
 * @test
 * @summary With -Dsun.java2d.skia.text=shaper the Skia backend shapes drawString's
 *          text with Skia's shaper (HarfBuzz through Skia, Skia's own font
 *          fallback) and draws it through Skia's text path, instead of Java's
 *          layout and glyph images: the backend counts the glyphs it shaped,
 *          Latin text lands within two pixels of Java's layout (both shape with
 *          HarfBuzz and fractional advances), and a string the font lacks (CJK)
 *          still has ink through Skia's fallback.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=shaper -Dsun.java2d.skia.text.smoothing=true SkiaShaperTest
 */
public class SkiaShaperTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final String CJK = "日本語テキスト 中文文本 한국어";
    static final int W = 400, H = 40;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        Font font = new Font("Helvetica Neue", Font.PLAIN, 13);
        long before = shaped();
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font, TEXT);
        BufferedImage skia = onScreen(gc, font, TEXT);
        long shaped = shaped() - before;
        if (shaped < TEXT.replace(" ", "").length()) {
            throw new RuntimeException("Skia shaped " + shaped + " glyphs, expected at least " + TEXT.replace(" ", "").length());
        }
        Rectangle rb = inkBounds(reference), sb = inkBounds(skia);
        System.out.println("Latin: Java layout bounds " + rb + ", Skia shaping bounds " + sb + ", glyphs shaped " + shaped);
        if (Math.abs(rb.x - sb.x) > 2 || Math.abs(rb.y - sb.y) > 2 || Math.abs(rb.width - sb.width) > 4 || Math.abs(rb.height - sb.height) > 4) {
            throw new RuntimeException("Skia's shaping does not land where Java's layout does: " + rb + " vs " + sb);
        }
        before = shaped();
        BufferedImage cjk = onScreen(gc, font, CJK);
        Rectangle cb = inkBounds(cjk);
        System.out.println("CJK through Skia's fallback: bounds " + cb + ", glyphs shaped " + (shaped() - before));
        if (cb.width < 100 || cb.height < 8) {
            throw new RuntimeException("no ink for text the font lacks: " + cb);
        }
    }

    static long shaped() throws Exception {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsShapedBySkia");
            return ((Number) m.invoke(null)).longValue();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("the backend has no Skia shaping (no glyph counter)", e);
        }
    }

    static BufferedImage onScreen(GraphicsConfiguration gc, Font font, String text) {
        BufferedImage out;
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font, text);
            out = vi.getSnapshot();
        } while (vi.contentsLost());
        return out;
    }

    static void paint(Graphics2D g, Font font, String text) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, 4, 26);
        g.dispose();
    }

    static Rectangle inkBounds(BufferedImage im) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((im.getRGB(x, y) & 0xffffff) != 0xffffff) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }
}
