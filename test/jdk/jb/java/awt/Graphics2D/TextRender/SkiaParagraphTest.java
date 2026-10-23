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
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.text.AttributedString;
import java.util.function.Consumer;

/**
 * @test
 * @summary With -Dsun.java2d.skia.text=paragraph the Skia backend lays out attributed
 *          strings (drawString(AttributedCharacterIterator)) with Skia's paragraph
 *          engine (SkParagraph: runs of fonts and colours, underline, strikethrough)
 *          instead of Java's TextLayout: the backend counts the paragraphs it laid
 *          out, the text lands within two pixels of Java's layout, the underline is
 *          drawn below the baseline, an attribute the route does not support
 *          (superscript) still draws, through Java's layout, and the strike's
 *          edging reaches the paragraph (aliased text has no gray pixels).
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=paragraph -Dsun.java2d.skia.text.smoothing=true SkiaParagraphTest
 */
public class SkiaParagraphTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 400, H = 40, BASE = 24;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        Font font = new Font("Helvetica Neue", Font.PLAIN, 13);
        Font bold = font.deriveFont(Font.BOLD);

        // 1. plain attributed text: Java's layout and Skia's paragraph land in the same place
        Consumer<Graphics2D> plain = g -> {
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            g.drawString(as.getIterator(), 4, BASE);
        };
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), plain);
        long before = paragraphs();
        BufferedImage skia = onScreen(gc, plain);
        if (paragraphs() - before < 1) {
            throw new RuntimeException("Skia laid out " + (paragraphs() - before) + " paragraphs, expected at least 1");
        }
        Rectangle rb = inkBounds(reference), sb = inkBounds(skia);
        System.out.println("plain: Java layout " + rb + ", Skia paragraph " + sb);
        if (Math.abs(rb.x - sb.x) > 2 || Math.abs(rb.y - sb.y) > 2 || Math.abs(rb.width - sb.width) > 4 || Math.abs(rb.height - sb.height) > 4) {
            throw new RuntimeException("Skia's paragraph does not land where Java's layout does: " + rb + " vs " + sb);
        }

        // 2. underline, strikethrough, a bold run and a coloured run: all drawn by Skia, the underline below the baseline
        Consumer<Graphics2D> styled = g -> {
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            as.addAttribute(TextAttribute.FONT, bold, 0, 13);
            as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            as.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, 14, 19);
            as.addAttribute(TextAttribute.FOREGROUND, Color.RED, 20, 23);
            g.drawString(as.getIterator(), 4, BASE);
        };
        BufferedImage styledRef = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(styledRef.createGraphics(), styled);
        before = paragraphs();
        BufferedImage styledSkia = onScreen(gc, styled);
        if (paragraphs() - before < 1) {
            throw new RuntimeException("the styled string was not laid out by Skia");
        }
        Rectangle srb = inkBounds(styledRef), ssb = inkBounds(styledSkia);
        System.out.println("styled: Java layout " + srb + ", Skia paragraph " + ssb);
        if (!hasInkBelow(styledSkia, BASE + 1)) {
            throw new RuntimeException("no underline below the baseline: " + ssb);
        }
        if (!hasColour(styledSkia, 0xff0000)) {
            throw new RuntimeException("the coloured run is not red");
        }
        if (Math.abs(srb.x - ssb.x) > 2 || Math.abs(srb.width - ssb.width) > 4 || Math.abs(srb.height - ssb.height) > 4) {
            throw new RuntimeException("styled text does not land where Java's layout does: " + srb + " vs " + ssb);
        }

        // 3. an attribute the route does not handle goes to Java's layout, and still draws
        Consumer<Graphics2D> superscript = g -> {
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            as.addAttribute(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUPER, 0, 3);
            g.drawString(as.getIterator(), 4, BASE);
        };
        before = paragraphs();
        BufferedImage fallback = onScreen(gc, superscript);
        if (paragraphs() - before != 0) {
            throw new RuntimeException("a superscript string was laid out by Skia's paragraph");
        }
        Rectangle fb = inkBounds(fallback);
        System.out.println("superscript (Java's layout): " + fb);
        if (fb.width < 200) {
            throw new RuntimeException("the superscript string was not drawn: " + fb);
        }

        // 4. the strike's edging reaches the paragraph: aliased text has no gray pixels
        checkAliased(gc, font);
    }

    static void checkAliased(GraphicsConfiguration gc, Font font) throws Exception {
        Consumer<Graphics2D> aliased = g -> {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            AttributedString as = new AttributedString(TEXT);
            as.addAttribute(TextAttribute.FONT, font);
            g.drawString(as.getIterator(), 4, BASE);
        };
        long before = paragraphs();
        BufferedImage im = onScreen(gc, aliased);
        if (paragraphs() - before < 1) {
            throw new RuntimeException("the aliased string was not laid out by Skia");
        }
        int gray = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int v = im.getRGB(x, y) & 0xff;
            if (v != 0 && v != 0xff) gray++;
        }
        System.out.println("aliased: " + gray + " gray pixels, bounds " + inkBounds(im));
        if (gray > 0) {
            throw new RuntimeException("aliased text was drawn anti-aliased by Skia's paragraph: " + gray + " gray pixels");
        }
    }

    static long paragraphs() throws Exception {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia");
            return ((Number) m.invoke(null)).longValue();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("the backend has no Skia paragraph layout (no counter)", e);
        }
    }

    static BufferedImage onScreen(GraphicsConfiguration gc, Consumer<Graphics2D> draw) {
        BufferedImage out;
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), draw);
            out = vi.getSnapshot();
        } while (vi.contentsLost());
        return out;
    }

    static void paint(Graphics2D g, Consumer<Graphics2D> draw) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        draw.accept(g);
        g.dispose();
    }

    static Rectangle inkBounds(BufferedImage im) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((im.getRGB(x, y) & 0xffffff) != 0xffffff) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    static boolean hasInkBelow(BufferedImage im, int y0) {
        for (int y = y0; y < H; y++) for (int x = 0; x < W; x++) if ((im.getRGB(x, y) & 0xffffff) != 0xffffff) return true;
        return false;
    }

    static boolean hasColour(BufferedImage im, int rgb) {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = im.getRGB(x, y);
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (r > 200 && g < 120 && b < 120) return true;
        }
        return false;
    }
}
