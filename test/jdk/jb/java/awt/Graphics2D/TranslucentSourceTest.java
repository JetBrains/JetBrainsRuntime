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
 * @summary Translucent sources on a GPU pipeline. Src and SrcIn with a
 *          translucent colour and SrcOver with a constant alpha write what
 *          the software loops write (Metal writes premultiplied values under
 *          Src and SrcIn, the loops do not). A gradient between colours of
 *          different alpha interpolates premultiplied, as the GPU pipelines
 *          do with the premultiplied stops they are given; the software
 *          loops interpolate the raw ARGB values, so that region is checked
 *          against a premultiplied reference computed here.
 *          Metal has no run line because of the Src and SrcIn regions.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 TranslucentSourceTest skia
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public class TranslucentSourceTest {

    private static final int W = 240;
    private static final int H = 160;
    private static final int TOLERANCE = 4;
    private static final float GX0 = 160, GY0 = 20, GX1 = 230, GY1 = 140;
    private static final Color GC0 = new Color(255, 200, 0, 255);
    private static final Color GC1 = new Color(0, 120, 255, 32);

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        BufferedImage reference = PipelineComparison.softwareReference(W, H, TranslucentSourceTest::paint);
        BufferedImage actual = PipelineComparison.rendered(gc, W, H, TranslucentSourceTest::paint);
        paintPremultipliedGradient(reference);
        Rectangle[] regions = { new Rectangle(10, 20, 40, 120), new Rectangle(60, 20, 40, 120),
                                new Rectangle(110, 20, 40, 120), new Rectangle(160, 20, 70, 120) };
        String[] names = { "Src with a translucent colour", "SrcIn with a translucent colour",
                           "SrcOver with alpha 0.5", "gradient between colours of different alpha" };
        StringBuilder failures = new StringBuilder();
        for (int r = 0; r < regions.length; r++) {
            PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, regions[r], TOLERANCE);
            System.out.println(names[r] + ": max " + difference.max() + " levels, "
                    + difference.beyond() + " pixels beyond " + TOLERANCE);
            if (difference.beyond() > 0) {
                failures.append(names[r]).append(" (").append(difference.beyond()).append(" pixels, max ")
                        .append(difference.max()).append(") ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Translucent sources differ from the reference by more than "
                    + TOLERANCE + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g) {
        g.setColor(new Color(0xF0F0F0));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0x203040));
        g.fillRect(0, 60, W, 40);
        Color translucent = new Color(200, 40, 40, 96);
        // Src and SrcIn with a translucent colour on an opaque destination
        g.setComposite(AlphaComposite.Src);
        g.setColor(translucent);
        g.fillRect(10, 20, 40, 120);
        g.setComposite(AlphaComposite.SrcIn);
        g.fillRect(60, 20, 40, 120);
        // SrcOver with a constant alpha
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(new Color(40, 160, 40));
        g.fillRect(110, 20, 40, 120);
        // a gradient between colours of different alpha
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(new GradientPaint(GX0, GY0, GC0, GX1, GY1, GC1));
        g.fillRect(160, 20, 70, 120);
    }

    /** Replaces the gradient region of the software reference by a premultiplied interpolation. */
    private static void paintPremultipliedGradient(BufferedImage reference) {
        float dx = GX1 - GX0;
        float dy = GY1 - GY0;
        float len2 = dx * dx + dy * dy;
        float[] c0 = premultiplied(GC0);
        float[] c1 = premultiplied(GC1);
        for (int y = 20; y < 140; y++) {
            for (int x = 160; x < 230; x++) {
                float t = ((x + 0.5f - GX0) * dx + (y + 0.5f - GY0) * dy) / len2;
                t = Math.max(0f, Math.min(1f, t));
                int dst = y >= 60 && y < 100 ? 0x203040 : 0xF0F0F0;   // the background under the gradient
                int[] out = new int[3];
                float alpha = c0[3] + (c1[3] - c0[3]) * t;
                for (int i = 0; i < 3; i++) {
                    float src = c0[i] + (c1[i] - c0[i]) * t;   // premultiplied
                    float d = ((dst >> (16 - 8 * i)) & 0xff) / 255f;
                    out[i] = Math.round((src + d * (1 - alpha)) * 255f);
                }
                reference.setRGB(x, y, (out[0] << 16) | (out[1] << 8) | out[2]);
            }
        }
    }

    private static float[] premultiplied(Color c) {
        float a = c.getAlpha() / 255f;
        return new float[] { c.getRed() / 255f * a, c.getGreen() / 255f * a, c.getBlue() / 255f * a, a };
    }
}
