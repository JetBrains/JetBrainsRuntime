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
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * @test
 * @summary A blit reads its source as it is at the blit, also when the source is drawn into
 *          right after it in the same frame and blitted again: an accelerated image and a
 *          cached BufferedImage (its texture copy updated when the image changes) are each
 *          drawn twice onto a surface with a repaint in between, and the first copy must show
 *          the first content, the second copy the second.
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaBlitSourceOrderTest
 * @run main/othervm -Dsun.java2d.uiScale=1 SkiaBlitSourceOrderTest
 */
public class SkiaBlitSourceOrderTest {
    static final int S = 20;

    public static void main(String[] args) {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage dst = gc.createCompatibleVolatileImage(200, 40, Transparency.OPAQUE);
        VolatileImage accelerated = gc.createCompatibleVolatileImage(S, S, Transparency.OPAQUE);
        BufferedImage cached = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
        for (int attempt = 0; attempt < 5; attempt++) {
            dst.validate(gc);
            accelerated.validate(gc);
            fill(accelerated, Color.RED);
            fill(cached, Color.RED);
            Graphics2D g = dst.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 200, 40);
            for (int i = 0; i < 4; i++) {
                g.drawImage(cached, 150, 0, null);   // a few draws: the image gets its texture copy
            }
            g.dispose();
            dst.getSnapshot();   // ends the frame: the sources were last drawn in an earlier one

            g = dst.createGraphics();
            g.drawImage(accelerated, 0, 0, null);    // red
            g.drawImage(cached, 100, 0, null);       // red
            fill(accelerated, Color.BLUE);
            fill(cached, Color.BLUE);
            g.drawImage(accelerated, 40, 0, null);   // blue
            g.drawImage(cached, 140, 0, null);       // blue
            g.dispose();
            BufferedImage out = dst.getSnapshot();
            if (dst.contentsLost() || accelerated.contentsLost()) {
                continue;
            }
            check(out, 10, "accelerated, first copy", Color.RED);
            check(out, 50, "accelerated, second copy", Color.BLUE);
            check(out, 110, "cached, first copy", Color.RED);
            check(out, 150, "cached, second copy", Color.BLUE);
            System.out.println("PASSED");
            return;
        }
        throw new RuntimeException("the surfaces kept losing their contents");
    }

    static void fill(Image img, Color c) {
        Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor(c);
        g.fillRect(0, 0, S, S);
        g.dispose();
    }

    static void check(BufferedImage out, int x, String what, Color expected) {
        int rgb = out.getRGB(x, 10) & 0xffffff;
        if (rgb != (expected.getRGB() & 0xffffff)) {
            throw new RuntimeException(what + ": " + Integer.toHexString(rgb) + ", expected " + Integer.toHexString(expected.getRGB() & 0xffffff));
        }
    }
}
