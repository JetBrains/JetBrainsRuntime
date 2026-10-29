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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The raster surface's workloads (HANDOVER.md §11.3), on a 1200 x 900 BufferedImage of the given
 * type: RasterShapesBench <image type> [frames]. Per workload, warm-up frames, then the median ms
 * per frame over the timed ones, one line "workload<TAB>ms". The arms differ by properties only
 * (-Dsun.java2d.skia.raster=true, and -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia
 * for Skia's text route); the frame's background fill is outside the timing.
 *
 * The kit's version of the Mac's build/skia-smoke/RasterShapesBench.java (not in the repository),
 * written from the workload names of the VM's 2026-10-28 record: same size and counts, its own
 * positions and shapes, so its numbers compare with the VM's in kind, not to the digit. LCD text
 * renders as LCD on the opaque type (1, INT_RGB, Swing's back buffer) only; on 2 and 3 the JDK
 * draws it grayscale on every pipe.
 */
public class RasterShapesBench {
    static final int W = 1200, H = 900;

    public static void main(String[] args) {
        int type = Integer.parseInt(args[0]);
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        BufferedImage target = new BufferedImage(W, H, type);
        target.createGraphics().dispose();
        // which pipeline draws (one field, not a result line)
        System.out.println("#surface:" + sun.java2d.SurfaceData.getPrimarySurfaceData(target).getClass().getSimpleName());
        Random r = new Random(1);
        float[] xs = new float[2000], ys = new float[2000];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = r.nextFloat() * (W - 80) + 0.25f;
            ys[i] = r.nextFloat() * (H - 80) + 0.25f;
        }
        BufferedImage icon = image(16, 16, BufferedImage.TYPE_INT_ARGB_PRE);
        BufferedImage picture = image(64, 48, BufferedImage.TYPE_INT_ARGB_PRE);
        Shape blob = blob();
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);

        run(target, frames, "rrect 24x24 fills x2000", true, g -> {
            for (int i = 0; i < 2000; i++) g.fill(new RoundRectangle2D.Float(xs[i], ys[i], 24, 24, 8, 8));
        });
        run(target, frames, "oval 40x40 fills x2000", true, g -> {
            for (int i = 0; i < 2000; i++) g.fill(new Ellipse2D.Float(xs[i], ys[i], 40, 40));
        });
        run(target, frames, "path (cubic blob) fills x500", true, g -> {
            for (int i = 0; i < 500; i++) {
                g.translate(xs[i], ys[i]);
                g.fill(blob);
                g.translate(-xs[i], -ys[i]);
            }
        });
        run(target, frames, "stroked rrect 2px x1000", true, g -> {
            g.setStroke(new BasicStroke(2f));
            for (int i = 0; i < 1000; i++) g.draw(new RoundRectangle2D.Float(xs[i], ys[i], 24, 24, 8, 8));
        });
        run(target, frames, "aa rect fills x2000 (converter)", true, g -> {
            for (int i = 0; i < 2000; i++) g.fill(new Rectangle2D.Float(xs[i], ys[i], 6.5f, 4.5f));
        });
        run(target, frames, "gradient rrect 24x24 fills x2000", true, g -> {
            for (int i = 0; i < 2000; i++) {
                g.setPaint(new GradientPaint(xs[i], ys[i], Color.BLUE, xs[i] + 24, ys[i] + 24, Color.ORANGE));
                g.fill(new RoundRectangle2D.Float(xs[i], ys[i], 24, 24, 8, 8));
            }
        });
        run(target, frames, "radial-gradient oval fills x500", true, g -> {
            for (int i = 0; i < 500; i++) {
                g.setPaint(new RadialGradientPaint(new Point2D.Float(xs[i] + 20, ys[i] + 20), 20,
                        new float[] { 0f, 0.6f, 1f }, new Color[] { Color.WHITE, Color.CYAN, Color.MAGENTA },
                        MultipleGradientPaint.CycleMethod.NO_CYCLE));
                g.fill(new Ellipse2D.Float(xs[i], ys[i], 40, 40));
            }
        });
        run(target, frames, "icon 16->20 bilinear x2000", false, g -> {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < 2000; i++) g.drawImage(icon, (int) xs[i], (int) ys[i], 20, 20, null);
        });
        run(target, frames, "image 64x48 rotated bilinear x500", false, g -> {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < 500; i++) {
                AffineTransform at = AffineTransform.getTranslateInstance(xs[i], ys[i]);
                at.rotate(0.3);
                g.drawImage(picture, at, null);
            }
        });
        run(target, frames, "image 64x48 scaled 1.5 bicubic x500", false, g -> {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int i = 0; i < 500; i++) g.drawImage(picture, (int) xs[i], (int) ys[i], 96, 72, null);
        });
        run(target, frames, "text 13pt gray x400 strings", false, g -> {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            for (int i = 0; i < 400; i++) g.drawString("Hamburgefonstiv 0123 ffi", xs[i], ys[i] + 13);
        });
        run(target, frames, "text 13pt LCD x400 strings", false, g -> {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setFont(font);
            for (int i = 0; i < 400; i++) g.drawString("Hamburgefonstiv 0123 ffi", xs[i], ys[i] + 13);
        });
        run(target, frames, "non-AA oval 40x40 fills x2000", false, g -> {
            for (int i = 0; i < 2000; i++) g.fill(new Ellipse2D.Float(xs[i], ys[i], 40, 40));
        });
        run(target, frames, "non-AA rrect 24x24 fills x2000", false, g -> {
            for (int i = 0; i < 2000; i++) g.fill(new RoundRectangle2D.Float(xs[i], ys[i], 24, 24, 8, 8));
        });
        run(target, frames, "non-AA rect fills x2000", false, g -> {
            for (int i = 0; i < 2000; i++) g.fillRect((int) xs[i], (int) ys[i], 24, 16);
        });
        run(target, frames, "non-AA stroked rrect 2px x1000", false, g -> {
            g.setStroke(new BasicStroke(2f));
            for (int i = 0; i < 1000; i++) g.draw(new RoundRectangle2D.Float(xs[i], ys[i], 24, 24, 8, 8));
        });
    }

    static void run(BufferedImage target, int frames, String name, boolean aa, Consumer<Graphics2D> work) {
        int warmup = Math.max(10, frames / 2);
        double[] ms = new double[frames];
        for (int f = 0; f < warmup + frames; f++) {
            Graphics2D g = target.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(new Color(0x3050a0));
            long start = System.nanoTime();
            work.accept(g);
            target.getRGB(W / 2, H / 2);   // reads the image: a deferred write-back would be counted
            long end = System.nanoTime();
            g.dispose();
            if (f >= warmup) {
                ms[f - warmup] = (end - start) / 1e6;
            }
        }
        Arrays.sort(ms);
        System.out.printf("%s\t%.3f%n", name, ms[frames / 2]);
    }

    static BufferedImage image(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(0xc0ff2020, true), w, h, new Color(0xff2040e0, true)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.drawLine(0, h / 2, w, h / 2);
        g.dispose();
        return img;
    }

    static Shape blob() {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(0, 20);
        p.curveTo(0, -5, 30, -5, 40, 15);
        p.curveTo(55, 40, 30, 60, 15, 50);
        p.curveTo(-5, 45, 10, 35, 0, 20);
        p.closePath();
        return p;
    }
}
