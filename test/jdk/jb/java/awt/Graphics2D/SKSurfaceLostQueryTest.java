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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import jtreg.SkippedException;
import sun.java2d.SurfaceData;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: asking a Skia surface whether it is lost does not wait for
 *          a flush of the render queue.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SKSurfaceLostQueryTest
 */

/*
 * VolatileImage.validate, the proxies and Swing's back buffer on every paint ask whether a surface
 * is lost. That read the device's lost flag under the backend's entry lock, which a flush holds
 * from start to end, so the event thread stalled behind every long flush. Here one flush is made
 * long on any GPU: translucent antialiased fills over a large image, as many as it takes for the
 * read-back that ends them to take at least LONG_FLUSH_MS; while three such flushes run,
 * another thread times isSurfaceLost on another surface. A query that waits for the flush takes
 * about as long as the flush; one that does not, a few microseconds. Where no flush gets that
 * long the test is skipped, saying so.
 */

/** The side of the large image the fills cover. */
static final int SIZE = 4096;
static final long LONG_FLUSH_MS = 200;

// a query may take at most 1/FLUSH_FRACTION of the flush, and at most MAX_QUERY_MS
static final int FLUSH_FRACTION = 4;
static final long MAX_QUERY_MS = 100;
/** Queries the scheduler may hold the querying thread off for longer than the limit; the next longest one counts. */
static final int OUTLIERS = 2;

// the fills are doubled from FIRST_FILLS until a flush takes LONG_FLUSH_MS, or MAX_FILLS is reached
static final int FIRST_FILLS = 16;
static final int MAX_FILLS = 1 << 15;

/** The side of the small image whose surface is queried. */
private static final int QUERIED_SIZE = 16;
/** How many long flushes run while the queries are timed; the shortest one sets the limit. */
private static final int MEASURED_FLUSHES = 3;
/** The querying thread runs this long before its timings count (its own warm-up). */
private static final long QUERIER_WARMUP_MS = 50;
/** The alpha of the fills: translucent, so that every fill blends. */
private static final int FILL_ALPHA = 96;

void main() throws Exception {
    PipelineComparison.requireRequestedAPI();
    GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
    VolatileImage small = graphicsConfig.createCompatibleVolatileImage(QUERIED_SIZE, QUERIED_SIZE);
    small.validate(graphicsConfig);
    SurfaceData queried = SurfaceData.getPrimarySurfaceData(small);
    if (!queried.getClass().getName().contains(PipelineComparison.SKIA_PACKAGE)) {
        throw new RuntimeException("the volatile image is not a Skia surface: " + queried);
    }
    VolatileImage big = graphicsConfig.createCompatibleVolatileImage(SIZE, SIZE);
    big.validate(graphicsConfig);
    if (!PipelineComparison.surfaceClassName(big).contains(PipelineComparison.SKIA_PACKAGE)) {
        throw new SkippedException(SIZE + "x" + SIZE + " is not a Skia surface: not measured");
    }
    BufferedImage target = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB_PRE);
    flush(big, target, 1);   // warm up: textures, pipelines, the staging buffers

    int fills = FIRST_FILLS;
    long flushNanos = flush(big, target, fills);
    while (flushNanos < LONG_FLUSH_MS * 1_000_000) {
        if (fills >= MAX_FILLS) {
            throw new SkippedException(String.format("could not produce a flush of >= %d ms (%.1f ms with %d fills): not measured",
                    LONG_FLUSH_MS, flushNanos / 1e6, fills));
        }
        fills *= 2;
        flushNanos = flush(big, target, fills);
    }
    System.out.printf("%d fills of %dx%d: a flush of %.1f ms%n", fills, SIZE, SIZE, flushNanos / 1e6);

    AtomicBoolean running = new AtomicBoolean(true);
    Longest longest = new Longest();
    Thread querier = new Thread(() -> {
        while (running.get()) {
            long start = System.nanoTime();
            //noinspection ResultOfMethodCallIgnored (the query is timed; its answer does not matter)
            queried.isSurfaceLost();
            long took = System.nanoTime() - start;
            longest.note(took);
            Thread.onSpinWait();
        }
    });
    querier.start();
    try {
        Thread.sleep(QUERIER_WARMUP_MS);
        longest.reset();
        flushNanos = Long.MAX_VALUE;
        for (int measured = 0; measured < MEASURED_FLUSHES; measured++) {
            flushNanos = Math.min(flushNanos, flush(big, target, fills));
        }
    } finally {
        running.set(false);   // a flush that fails must not leave the querier spinning, keeping the JVM alive
        querier.join();
    }
    big.flush();
    long limitNanos = Math.min(flushNanos / FLUSH_FRACTION, MAX_QUERY_MS * 1_000_000);
    long[] durations = longest.get();
    long counted = durations[OUTLIERS];
    System.out.printf("flush %.1f ms (shortest of %d), longest isSurfaceLost %s ms, limit %.1f ms%n",
            flushNanos / 1e6, MEASURED_FLUSHES,
            String.join(", ", Arrays.stream(durations).mapToObj(nanos -> String.format("%.3f", nanos / 1e6)).toList()),
            limitNanos / 1e6);
    if (flushNanos < LONG_FLUSH_MS * 1_000_000 / 2) {
        throw new SkippedException(String.format("the flushes shrank to %.1f ms while measured: not measured", flushNanos / 1e6));
    }
    if (counted > limitNanos) {
        throw new RuntimeException(String.format(
                "isSurfaceLost took %.1f ms (the longest %d queries aside) while a %.1f ms flush ran: it waits for the render queue",
                counted / 1e6, OUTLIERS, flushNanos / 1e6));
    }
    System.out.println("PASSED");
}

/** The longest query durations seen, descending: the outliers and the one that counts. */
private static final class Longest {
    private final long[] nanos = new long[OUTLIERS + 1];

    synchronized void note(long took) {
        for (int index = 0; index < nanos.length; index++) {
            if (took > nanos[index]) {
                System.arraycopy(nanos, index, nanos, index + 1, nanos.length - index - 1);
                nanos[index] = took;
                return;
            }
        }
    }

    synchronized void reset() {
        Arrays.fill(nanos, 0);
    }

    synchronized long[] get() {
        return nanos.clone();
    }
}

/**
 * Queues the fills into the image and reads it back; the time the read-back took. Graphite
 * records the fills as they are flushed into it and submits them with the read-back, whose
 * flush then waits for the GPU to draw them all (under the entry lock).
 */
long flush(VolatileImage image, BufferedImage target, int fills) {
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    for (int fill = 0; fill < fills; fill++) {
        // a different color and a slightly different place for every fill, so none is a repeat
        graphics.setColor(new Color((fill * 37) & 0xff, (fill * 91) & 0xff, (fill * 53) & 0xff, FILL_ALPHA));
        graphics.fill(new Ellipse2D.Float(fill % 7, fill % 5, SIZE - 8, SIZE - 8));
    }
    graphics.dispose();
    long start = System.nanoTime();
    Graphics2D targetGraphics = target.createGraphics();
    targetGraphics.drawImage(image, 0, 0, null);
    targetGraphics.dispose();
    return System.nanoTime() - start;
}
