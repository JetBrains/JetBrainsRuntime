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

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.function.Consumer;
import sun.java2d.SurfaceData;

/**
 * Renders the same painter on a GPU pipeline and with the software loops
 * and compares the two: the shared part of the tests that hold a pipeline
 * to the loops within a measured tolerance.
 */
public final class PipelineComparison {

    private PipelineComparison() {
    }

    /** Whether the pipeline named on the run line owns the configuration's volatile surfaces; otherwise there is nothing to test. */
    public static boolean pipelineActive(String pipeline, GraphicsConfiguration gc) {
        VolatileImage probe = gc.createCompatibleVolatileImage(16, 16, Transparency.OPAQUE);
        probe.validate(gc);
        String surface = SurfaceData.getPrimarySurfaceData(probe).getClass().getName();
        boolean active = switch (pipeline) {
            case "skia" -> surface.contains(".skia.");
            case "metal" -> surface.contains(".metal.");
            default -> throw new IllegalArgumentException("unknown pipeline " + pipeline);
        };
        System.out.println("pipeline " + pipeline + ", surface " + surface + (active ? "" : ": nothing to test"));
        return active;
    }

    /** The painter's output on the software loops. */
    public static BufferedImage softwareReference(int w, int h, Consumer<Graphics2D> painter) {
        BufferedImage reference = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = reference.createGraphics();
        try {
            painter.accept(g);
        } finally {
            g.dispose();
        }
        return reference;
    }

    /** The painter's output on a new opaque volatile image of the configuration. */
    public static BufferedImage rendered(GraphicsConfiguration gc, int w, int h, Consumer<Graphics2D> painter) {
        VolatileImage image = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
        image.validate(gc);
        return rendered(image, painter);
    }

    /** The painter's output on the given volatile image, for tests that paint several frames into one image. */
    public static BufferedImage rendered(VolatileImage image, Consumer<Graphics2D> painter) {
        Graphics2D g = image.createGraphics();
        try {
            painter.accept(g);
        } finally {
            g.dispose();
        }
        BufferedImage snapshot = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        return snapshot;
    }

    public static int maxChannelDifference(int p, int q) {
        int r = Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff));
        int g = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(g, b));
    }

    /**
     * @param beyond     pixels whose largest channel difference exceeds the tolerance
     * @param max        the largest channel difference of the region
     * @param missingInk pixels inked in the reference (blue below 128) that are
     *                   background in the rendering (blue at 255)
     */
    public record Difference(int beyond, int max, int missingInk) {
    }

    public static Difference compare(BufferedImage reference, BufferedImage actual, int tolerance) {
        return compare(reference, actual, new Rectangle(reference.getWidth(), reference.getHeight()), tolerance);
    }

    public static Difference compare(BufferedImage reference, BufferedImage actual, Rectangle region, int tolerance) {
        int beyond = 0;
        int max = 0;
        int missingInk = 0;
        for (int y = region.y; y < region.y + region.height; y++) {
            for (int x = region.x; x < region.x + region.width; x++) {
                int p = reference.getRGB(x, y);
                int q = actual.getRGB(x, y);
                int d = maxChannelDifference(p, q);
                max = Math.max(max, d);
                if (d > tolerance) {
                    beyond++;
                }
                if ((p & 0xff) < 128 && (q & 0xff) == 0xff) {
                    missingInk++;
                }
            }
        }
        return new Difference(beyond, max, missingInk);
    }
}
