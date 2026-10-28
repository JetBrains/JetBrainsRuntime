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
 * @summary The fixed cost of one antialiased operation on a Skia raster
 *          surface: thousands of small antialiased rectangles, the converter's
 *          parallelogram fills, must not take more than 1.5 times the software
 *          loops' time for the same drawing on INT_ARGB_PRE (they took 3.9
 *          times when every operation created its canvas, copied its arrays
 *          and, declared BGRA, ran Skia's generic blitter instead of its N32
 *          one), and not more than 2.5 times on INT_ARGB, whose pixels are
 *          converted into and out of a premultiplied mirror per operation, a
 *          cost in the touched area rather than per call. Median of
 *          interleaved frames.
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
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage skia = new BufferedImage(W, H, type);
            double[] a = new double[FRAMES], b = new double[FRAMES];
            for (int f = 0; f < FRAMES; f++) {   // interleaved: the same frame on both surfaces
                a[f] = frame(skia, f); b[f] = frame(loops, f);
            }
            double ms = median(a), ml = median(b), ratio = ms / ml;
            double limit = type == BufferedImage.TYPE_INT_ARGB ? 2.5 : 1.5;
            System.out.printf("type %d: %d AA rects per frame, Skia raster %.2f ms, loops %.2f ms, ratio %.2f (limit %.1f)%n", type, N, ms, ml, ratio, limit);
            if (ratio > limit) {
                throw new RuntimeException("type " + type + ": the raster surface takes " + ratio + " times the loops' time for small AA rectangles (limit " + limit + ")");
            }
        }
        System.out.println("PASSED");
    }

    static double frame(BufferedImage img, int seed) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        Random rnd = new Random(seed);
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            g.setColor(new Color(rnd.nextInt(0xffffff)));
            g.fill(new Rectangle2D.Double(rnd.nextInt(W - 40) + rnd.nextDouble(), rnd.nextInt(H - 40) + rnd.nextDouble(), 30, 20));
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        g.dispose();
        return ms;
    }

    static double median(double[] v) { double[] t = Arrays.copyOfRange(v, 2, v.length); Arrays.sort(t); return t[t.length / 2]; }
}
