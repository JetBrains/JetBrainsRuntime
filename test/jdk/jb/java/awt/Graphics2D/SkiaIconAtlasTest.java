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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary With sun.java2d.skia.iconAtlas, unscaled blits of small accelerated images (icons) are drawn
 *          from an atlas of their copies as sprites of a few meshes, not one Graphite draw each. A frame of
 *          200 icons of 16 images, in cell clips that cut some of them, under rect fills drawn over some
 *          and over rect fills drawn before others, some at half extra alpha, and one icon changed in the
 *          middle of the frame (the sprites drawn before show its old pixels, those after its new ones),
 *          matches the software loops; the atlas draws the icons in at most 25 mesh draws (200 without it). Without the
 *          property (the last run line) the frame matches as well and nothing is drawn from the atlas.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.iconAtlas=true SkiaIconAtlasTest on
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan -Dsun.java2d.skia.iconAtlas=true SkiaIconAtlasTest on
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaIconAtlasTest off
 */
public class SkiaIconAtlasTest {
    static final int W = 960, H = 200;
    static final int ICONS = 16, ICON = 16, BLITS = 200;
    static final int CHANGED = 3;   // the icon redrawn in the middle of the frame
    // forced by the frame (16 on Dawn and Vulkan, 2026-11-04): a rect over an icon (10) and an extra alpha (8)
    // draw the pending sprites first, so do the changed icon and the frame's end; without the
    // atlas every icon is a draw of its own (200)
    static final int MAX_BATCH_DRAWS = 25;

    public static void main(String[] args) throws Exception {
        boolean on = args[0].equals("on");
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        if (!(Boolean) env.getMethod("isSkiaEnabled").invoke(null)) {
            System.out.println("Skia is not available: nothing to test");
            return;
        }
        Method stats = env.getMethod("iconAtlasStats");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage[] pixels = new BufferedImage[ICONS];
        VolatileImage[] icons = new VolatileImage[ICONS];
        for (int i = 0; i < ICONS; i++) {
            pixels[i] = icon(i, false);
            icons[i] = gc.createCompatibleVolatileImage(ICON, ICON, Transparency.TRANSLUCENT);
        }
        BufferedImage changed = icon(CHANGED, true);
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage actual;
        long[] before, after;
        boolean lost;
        do {
            vi.validate(gc);
            for (int i = 0; i < ICONS; i++) {
                icons[i].validate(gc);
                copy(pixels[i], icons[i]);
            }
            paint(vi.createGraphics(), icons, changed);   // the atlas takes its copies
            vi.getSnapshot();
            for (int i = 0; i < ICONS; i++) {
                copy(pixels[i], icons[i]);   // the changed icon back to its first pixels
            }
            before = (long[]) stats.invoke(null);
            paint(vi.createGraphics(), icons, changed);
            actual = vi.getSnapshot();
            after = (long[]) stats.invoke(null);
            lost = vi.contentsLost();
            for (VolatileImage icon : icons) {
                lost |= icon.contentsLost();
            }
        } while (lost);
        BufferedImage[] reference = new BufferedImage[ICONS];
        for (int i = 0; i < ICONS; i++) {
            reference[i] = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
            copy(pixels[i], reference[i]);
        }
        BufferedImage expected = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(expected.createGraphics(), reference, changed);

        int beyond = 0, max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = expected.getRGB(x, y), q = actual.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff)),
                        Math.max(Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff)), Math.abs((p & 0xff) - (q & 0xff))));
                max = Math.max(max, d);
                if (d > 4) {
                    beyond++;
                }
            }
        }
        long blits = after[0] - before[0], copies = after[1] - before[1], draws = after[3] - before[3];
        System.out.println(BLITS + " icon blits: " + blits + " from the atlas, " + copies + " copies into it, " + draws
                + " atlas batch draws; against the software loops max " + max + " levels, " + beyond + " pixels beyond 4");
        if (beyond > 0) {
            throw new RuntimeException("the icons differ from the software loops: " + beyond + " pixels beyond 4 levels (max " + max + ")");
        }
        if (!on) {
            if (blits != 0) {
                throw new RuntimeException(blits + " blits drawn from the atlas without sun.java2d.skia.iconAtlas");
            }
            System.out.println("PASSED");
            return;
        }
        if (blits < BLITS * 9 / 10) {
            throw new RuntimeException("only " + blits + " of " + BLITS + " icon blits drawn from the atlas");
        }
        if (copies < 2) {
            throw new RuntimeException(copies + " copies into the atlas: the changed icon was not copied again (twice per frame)");
        }
        if (draws > MAX_BATCH_DRAWS) {
            throw new RuntimeException(draws + " atlas batch draws for " + blits + " icons (at most " + MAX_BATCH_DRAWS + ")");
        }
        System.out.println("PASSED");
    }

    // A translucent icon: a pattern in its own colours with a soft edge; `changed` gives it other colours.
    static BufferedImage icon(int i, boolean changed) {
        BufferedImage img = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        int a = changed ? 0x2060e0 : (0x101010 * (i + 1)) ^ (i * 0x3a1f05);
        int b = changed ? 0xe0e020 : 0xf0f0f0 - (a & 0x7f7f7f);
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                int rgb = (x / 4 + y / 4 + i) % 2 == 0 ? a : b;
                int alpha = x == 0 || y == 0 || x == ICON - 1 || y == ICON - 1 ? 0x80 : 0xff;
                img.setRGB(x, y, (alpha << 24) | (rgb & 0xffffff));
            }
        }
        return img;
    }

    static void copy(BufferedImage from, Image to) {
        Graphics2D g = (Graphics2D) to.getGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(from, 0, 0, null);
        g.dispose();
    }

    static void paint(Graphics2D g, Image[] icons, BufferedImage changed) {
        g.setColor(new Color(0xf4f4f0));
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < BLITS; i++) {
            int x = 6 + (i % 40) * 23, y = 8 + (i / 40) * 38;
            // a cell's clip; every seventh cuts its icon
            g.setClip(x - 2, y - 2, i % 7 == 0 ? 12 : 22, i % 7 == 3 ? 10 : 34);
            if (i % 5 == 1) {
                g.setColor(new Color(0x30a050));
                g.fillRect(x + 4, y + 4, 12, 20);   // beneath the icon: drawn before it
            }
            if (i % 25 == 2) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            }
            g.drawImage(icons[i % ICONS], x, y, null);
            g.setComposite(AlphaComposite.SrcOver);
            if (i % 20 == 3) {
                g.setColor(new Color(0xc03030));
                g.fillRect(x + 6, y + 6, 6, 14);    // over the icon: drawn after it
            }
            if (i == BLITS / 2) {
                copy(changed, icons[CHANGED]);   // the changed icon from here on
            }
        }
        g.setClip(null);
        g.dispose();
    }
}
