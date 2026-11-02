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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Random;

import sun.java2d.SurfaceData;

/**
 * @test
 * @summary The fixed cost of one operation on a Skia raster surface, measured on
 *          thousands of small rectangles against the software loops, median of
 *          interleaved frames. Antialiased fractional rectangles (the converter's
 *          parallelogram fills) may take 1.5 times the loops' time on INT_ARGB_PRE
 *          (3.9 when every operation created its canvas, copied its arrays and ran
 *          Skia's generic blitter) and 1.5 on INT_ARGB too, whose pixels go through a
 *          premultiplied mirror the thread keeps while nothing else writes them (2.5
 *          when every operation converted its box in and out). Non-antialiased
 *          integer rectangles are the loops' cheapest call, a store under the same
 *          lock, so only the fixed cost shows: 1.25 and 1.5 times (3.5 before the
 *          colour-only rectangle entry that locks, stores and leaves).
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterFixedCostTest
 */
public class SkiaRasterFixedCostTest {
    static final int W = 1200, H = 900, N = 2000, FRAMES = 12;

    public static void main(String[] args) {
        BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        if (!SurfaceData.getPrimarySurfaceData(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)).getClass().getSimpleName().equals("SKRasterSurfaceData")) {
            throw new RuntimeException("no Skia raster surface: is the backend's library available?");
        }
        for (boolean aa : new boolean[] {true, false}) {
            for (int type : new int[] {BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_ARGB}) {
                BufferedImage skia = new BufferedImage(W, H, type);
                double[] a = new double[FRAMES], b = new double[FRAMES];
                for (int f = 0; f < FRAMES; f++) {   // interleaved: the same frame on both surfaces
                    a[f] = frame(skia, f, aa, Shape.RECT); b[f] = frame(loops, f, aa, Shape.RECT);
                }
                double ms = median(a), ml = median(b), ratio = ms / ml;
                // AA: the rasterizer's work dominates; non-AA integer rectangles are the loops' cheapest
                // operation (a memset-like loop under the same lock), so only the fixed cost shows
                double limit = aa ? (type == BufferedImage.TYPE_INT_ARGB ? 1.5 : 1.5) : (type == BufferedImage.TYPE_INT_ARGB ? 1.5 : 1.25);
                System.out.printf("type %d: %d %s rects per frame, Skia raster %.2f ms, loops %.2f ms, ratio %.2f (limit %.2f)%n", type, N, aa ? "AA" : "non-AA integer", ms, ml, ratio, limit);
                if (ratio > limit) {
                    throw new RuntimeException("type " + type + ": the raster surface takes " + ratio + " times the loops' time for small " + (aa ? "AA" : "non-AA integer") + " rectangles (limit " + limit + ")");
                }
            }
        }
        // fixed cost round 3 (2026-11-02): the two RenderPerfTest rows the raster surface lost on the Mac —
        // rotated boxes without antialiasing (0.48x: a Path2D, its arrays and Skia's path fill per box where the
        // loops run FillParallelogram) and antialiased lines (0.75x: the converter's pen parallelogram as a path)
        for (Shape shape : new Shape[] {Shape.ROTATED_BOX, Shape.LINE}) {
            boolean aa = shape == Shape.LINE;
            BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
            double[] a = new double[FRAMES], b = new double[FRAMES];
            for (int f = 0; f < FRAMES; f++) {
                a[f] = frame(skia, f, aa, shape); b[f] = frame(loops, f, aa, shape);
            }
            double ms = median(a), ml = median(b), ratio = ms / ml, limit = shape == Shape.LINE ? 1.2 : 1.25;
            System.out.printf("%d %s per frame, Skia raster %.2f ms, loops %.2f ms, ratio %.2f (limit %.2f)%n", N, shape, ms, ml, ratio, limit);
            if (ratio > limit) {
                throw new RuntimeException("the raster surface takes " + ratio + " times the loops' time for " + shape + " (limit " + limit + ")");
            }
        }
        System.out.println("PASSED");
    }

    enum Shape { RECT, ROTATED_BOX, LINE }

    static double frame(BufferedImage img, int seed, boolean aa, Shape shape) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        Random rnd = new Random(seed);
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            g.setColor(new Color(rnd.nextInt(0xffffff)));
            switch (shape) {
                case RECT -> {
                    if (aa) {
                        g.fill(new Rectangle2D.Double(rnd.nextInt(W - 40) + rnd.nextDouble(), rnd.nextInt(H - 40) + rnd.nextDouble(), 30, 20));
                    } else {
                        g.fillRect(rnd.nextInt(W - 40), rnd.nextInt(H - 40), 30, 20);
                    }
                }
                case ROTATED_BOX -> {   // RenderPerfTest's RotatedBox: translate, rotate, fillRect
                    java.awt.geom.AffineTransform t = g.getTransform();
                    g.translate(rnd.nextInt(W - 40) + 20, rnd.nextInt(H - 40) + 20);
                    g.rotate(rnd.nextDouble() * Math.PI);
                    g.fillRect(-10, -10, 20, 20);
                    g.setTransform(t);
                }
                case LINE -> {   // RenderPerfTest's LinesAA: an integer line of about 30 px
                    int x = rnd.nextInt(W - 60) + 30, y = rnd.nextInt(H - 60) + 30; double ang = rnd.nextDouble() * Math.PI * 2;
                    g.drawLine(x, y, x + (int) (30 * Math.cos(ang)), y + (int) (30 * Math.sin(ang)));
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        g.dispose();
        return ms;
    }

    static double median(double[] v) { double[] t = Arrays.copyOfRange(v, 2, v.length); Arrays.sort(t); return t[t.length / 2]; }
}
