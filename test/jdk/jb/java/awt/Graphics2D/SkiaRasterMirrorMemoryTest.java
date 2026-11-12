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
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * @test
 * @summary An INT_ARGB raster surface's premultiplied mirror (a copy of the whole image) belongs to the image, not to
 *          the threads that draw into it: it is released with the image, the live mirrors stay within their budget
 *          (sun.java2d.skia.raster.mirrorBudget, MB), and threads that take turns on an image share its mirror. Each
 *          thread kept the mirrors of the last four images it drew into until it drew into others or ended, so
 *          idle threads held the mirrors of images long collected (eight threads, 370 MB for 2400 x 1600 images),
 *          and a thread drawing after another threw the other's work away and imported the pixels again.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Dsun.java2d.skia.raster.mirrorBudget=24 -Djava.awt.headless=true SkiaRasterMirrorMemoryTest
 */
public class SkiaRasterMirrorMemoryTest {
    static final int THREADS = 8, W = 1200, H = 900, BUDGET = 24 << 20, MIRROR = W * H * 4;

    public static void main(String[] args) throws Exception {
        StringBuilder failures = new StringBuilder();
        List<ExecutorService> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) threads.add(Executors.newSingleThreadExecutor());

        // images drawn on every thread, then dropped: the threads stay alive and idle
        for (ExecutorService thread : threads) {
            thread.submit(() -> {
                for (int i = 0; i < 4; i++) ovals(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB), new Random(i), 20);
            }).get();
        }
        long dropped = settled();
        System.out.printf("%d threads drew into 4 images each, the images dropped: the mirrors hold %.1f MB%n", THREADS, dropped / 1e6);
        if (dropped > 0) failures.append(String.format("the mirrors of collected images hold %.1f MB; ", dropped / 1e6));

        // images kept alive, two drawn on every thread: no more than the budget, and the image being drawn
        List<BufferedImage> live = new ArrayList<>();
        long peak = 0;
        for (ExecutorService thread : threads) {
            peak = Math.max(peak, thread.submit(() -> {
                long most = 0;
                for (int i = 0; i < 2; i++) {
                    BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                    synchronized (live) { live.add(image); }
                    ovals(image, new Random(i), 20);
                    most = Math.max(most, mirrorBytes());
                }
                return most;
            }).get());
        }
        System.out.printf("%d live images drawn on %d threads: the mirrors held up to %.1f MB (budget %d MB, a mirror %.1f MB)%n",
                live.size(), THREADS, peak / 1e6, BUDGET >> 20, MIRROR / 1e6);
        if (peak > BUDGET + MIRROR) failures.append(String.format("%d live images' mirrors held %.1f MB, over the %d MB budget; ", live.size(), peak / 1e6, BUDGET >> 20));

        // an image evicted from the budget and drawn again carries on where it was (the pixels imported again)
        BufferedImage evicted = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB), whole = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        ovals(evicted, new Random(7), 30);
        for (BufferedImage image : live) ovals(image, new Random(9), 5);
        ovals(evicted, new Random(8), 30);
        ovals(whole, new Random(7), 30);
        ovals(whole, new Random(8), 30);
        int evictedOff = differing(evicted, whole, 2);
        System.out.println("an image evicted between two batches: " + evictedOff + " pixels off its uninterrupted drawing by more than 2 levels");
        if (evictedOff > 0) failures.append(evictedOff).append(" pixels of an evicted image off by more than 2 levels; ");
        live.clear();
        settled();

        // two threads taking turns on one image: one mirror, kept across the turns, the pixels as one thread's
        BufferedImage turns = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB), single = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Random a = new Random(3), b = new Random(3);
        threads.get(0).submit(() -> ovals(turns, a, 1)).get();
        long before = invalidations();
        for (int i = 0; i < 200; i++) {
            ExecutorService thread = threads.get(1 + i % 2);
            thread.submit(() -> ovals(turns, a, 1)).get();
        }
        long thrownAway = invalidations() - before;
        for (int i = 0; i < 201; i++) ovals(single, b, 1);
        int turnsOff = differing(turns, single, 0);
        System.out.println("two threads taking 200 turns on one image: the mirror thrown away " + thrownAway + " times, "
                + turnsOff + " pixels differ from one thread's drawing");
        if (thrownAway > 2) failures.append("two threads taking turns threw the mirror away ").append(thrownAway).append(" times in 200 operations; ");
        if (turnsOff > 0) failures.append(turnsOff).append(" pixels differ when two threads take turns; ");

        for (ExecutorService thread : threads) thread.shutdown();
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
        System.out.println("PASSED");
    }

    /** Translucent antialiased ovals: the mirror's edges are partial alpha. */
    static void ovals(BufferedImage image, Random rnd, int count) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < count; i++) {
            g.setColor(new Color(rnd.nextInt(0xffffff) | (rnd.nextInt(156) + 100) << 24, true));
            g.fill(new Ellipse2D.Double(rnd.nextDouble() * (image.getWidth() - 80), rnd.nextDouble() * (image.getHeight() - 60), 80, 60));
        }
        g.dispose();
    }

    static int differing(BufferedImage x, BufferedImage y, int tolerance) {
        int count = 0;
        for (int j = 0; j < x.getHeight(); j++) for (int i = 0; i < x.getWidth(); i++) {
            int p = x.getRGB(i, j), q = y.getRGB(i, j);
            for (int s = 0; s < 32; s += 8) if (Math.abs(((p >> s) & 255) - ((q >> s) & 255)) > tolerance) { count++; break; }
        }
        return count;
    }

    /** The mirrors' bytes once the collector and the Disposer are done with the dropped images. */
    static long settled() throws Exception {
        long previous = -1;
        for (int i = 0; i < 60; i++) {
            System.gc();
            Thread.sleep(50);
            long now = mirrorBytes();
            if (now == 0 || (i >= 10 && now == previous)) return now;
            previous = now;
        }
        return previous;
    }

    static long mirrorBytes() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterMirrorBytes").invoke(null);
    }

    static long invalidations() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterMirrorInvalidations").invoke(null);
    }
}
