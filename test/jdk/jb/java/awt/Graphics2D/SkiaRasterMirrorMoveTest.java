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
import java.util.Random;

/**
 * @test
 * @summary An INT_ARGB raster surface keeps its premultiplied mirror across operations when the garbage collector
 *          moves the image's pixel array: the thread's raster target follows the array. A second target for the
 *          same surface left the first where the next operation's lookup found it, so every later operation saw
 *          another target and threw the mirror away (2.2-2.7 times the loops' time on small antialiased rectangles
 *          in SkiaRasterFixedCostTest whenever a GC moved the array during the run). Likewise when a surface dies and
 *          its native ops go to a new image of another type over the same memory (the old target, premultiplied,
 *          looked like the new surface's). Counted, not timed: the mirror's invalidations over batches of
 *          antialiased fills, a full GC before each batch; and over images dropped and replaced.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -XX:+UseG1GC -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterMirrorMoveTest
 */
public class SkiaRasterMirrorMoveTest {
    static final int BATCHES = 5, PER_BATCH = 400;

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB);   // well below a humongous region: movable
        Random rnd = new Random(1);
        fills(image, rnd, 50);   // the target bound, the mirror imported
        long before = invalidations();
        for (int b = 0; b < BATCHES; b++) {
            System.gc();   // G1's full collection compacts: the image's array moves
            fills(image, rnd, PER_BATCH);
        }
        long thrownAway = invalidations() - before;
        System.out.println(BATCHES * PER_BATCH + " antialiased fills in " + BATCHES + " batches, a GC before each: the mirror thrown away " + thrownAway + " times");
        // at most once a batch (the first operation after a move may re-import), never once an operation
        if (thrownAway > 2 * BATCHES) {
            throw new RuntimeException("the INT_ARGB mirror was thrown away " + thrownAway + " times in " + BATCHES * PER_BATCH + " operations");
        }
        // a premultiplied image of the same size dropped before each INT_ARGB one: its native ops and memory are
        // likely handed on, and the dead surface's target must not stand in for the new one's
        long recycled = 0;
        for (int round = 0; round < BATCHES; round++) {
            BufferedImage previous = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_ARGB_PRE);
            fills(previous, rnd, 50);
            previous = null;
            System.gc();
            BufferedImage next = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_ARGB);
            long start = invalidations();
            fills(next, rnd, PER_BATCH);
            recycled += invalidations() - start;
        }
        System.out.println(BATCHES + " INT_ARGB images, each after a dropped INT_ARGB_PRE one of the same size: the mirror thrown away " + recycled + " times in " + BATCHES * PER_BATCH + " operations");
        if (recycled > 2 * BATCHES) {
            throw new RuntimeException("the INT_ARGB mirror was thrown away " + recycled + " times in " + BATCHES * PER_BATCH + " operations after dropped surfaces");
        }
        System.out.println("PASSED");
    }

    static void fills(BufferedImage image, Random rnd, int count) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < count; i++) {
            g.setColor(new Color(rnd.nextInt(0xffffff)));
            g.fill(new Rectangle2D.Double(rnd.nextInt(image.getWidth() - 40) + rnd.nextDouble(), rnd.nextInt(image.getHeight() - 30) + rnd.nextDouble(), 30, 20));
        }
        g.dispose();
    }

    static long invalidations() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterMirrorInvalidations").invoke(null);
    }
}
