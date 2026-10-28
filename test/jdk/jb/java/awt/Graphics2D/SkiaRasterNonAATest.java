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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary With sun.java2d.skia.raster=true, non-antialiased fills and wide strokes
 *          on a BufferedImage are scan-converted by Skia: straight-edged shapes
 *          (rectangles under a fractional scale, rotated rectangles, polygons,
 *          mitered strokes) land pixel for pixel where the software loops put
 *          them, up to the ties of shallow edges; curved shapes may differ where
 *          the two flatteners disagree, on edge pixels only and on few of them.
 *          Thin draws (lines, ovals, round rectangles, arcs, a zero-width round
 *          rectangle that must still show as a line) are the loops' and identical.
 *          The loops' three fill rules are followed: ProcessPath's half-pixel
 *          offset for opaque fills under a thin stroke, the span iterator's
 *          quarter-pixel normalization otherwise and for strokes (Marlin's
 *          outline), the converter's for rectangles and parallelograms. Thin lines stay the loops'
 *          Bresenham and are identical. The pipe counts the shapes it drew.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterNonAATest
 */
public class SkiaRasterNonAATest {
    static final int W = 160, H = 120;
    /** Ties: a pixel centre exactly on an edge shallower than 1:64 resolves Skia's way (integer vertices, rational slopes). */
    static final double TIES = 0.02;
    /** Curves: the two flatteners disagree on a few edge pixels; ties as above. */
    static final double CURVES = 0.06;
    static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        compare("fillRect under scale 1.25", g -> { g.scale(1.25, 1.25); g.fillRect(10, 10, 50, 30); }, 0);
        compare("rotated rectangle", g -> { g.rotate(0.4, 80, 60); g.fill(new Rectangle2D.Double(40, 35, 80, 50)); }, TIES);
        compare("concave polygon", g -> g.fill(new Polygon(new int[] {10, 150, 150, 80, 10}, new int[] {10, 20, 110, 60, 100}, 5)), TIES);
        compare("mitered 3-px rectangle stroke", g -> { g.setStroke(new BasicStroke(3f)); g.draw(new Rectangle2D.Double(20.5, 15.5, 70, 40)); }, TIES);
        compare("thin line (the loops')", g -> g.drawLine(5, 5, 150, 97), 0);
        compare("thin oval and round rectangle draws (the loops' DrawPath)", g -> { g.drawOval(20, 15, 90, 60); g.drawRoundRect(120, 20, 30, 40, 8, 8); g.drawArc(30, 70, 50, 40, 20, 250); }, 0);
        compare("zero-width round rectangle draws as a line (the loops' DrawPath)", g -> { g.drawRoundRect(60, 20, 0, 40, 4, 4); g.drawRoundRect(20, 70, 40, 0, 4, 4); }, 0);
        compare("oval", g -> g.fill(new Ellipse2D.Double(20.5, 10.3, 60, 40)), 0.05);
        compare("round rectangle", g -> g.fill(new RoundRectangle2D.Double(30, 20, 60, 40, 12, 12)), 0.05);
        compare("cubic blob", g -> { Path2D.Double p = new Path2D.Double(); p.moveTo(20, 80); p.curveTo(40, 0, 120, 140, 140, 30); p.lineTo(120, 110); p.closePath(); g.fill(p); }, CURVES);
        compare("round-joined 4-px oval stroke", g -> { g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Ellipse2D.Double(30, 20, 90, 60)); }, CURVES);
        compare("translucent oval", g -> { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)); g.setColor(new Color(200, 30, 30)); g.fill(new Ellipse2D.Double(25, 15, 70, 50)); }, CURVES);
        long bySkia = nonAAShapesBySkia();
        if (bySkia == 0) {
            throw new RuntimeException("no non-antialiased shape reached Skia's raster path (the counter is 0)");
        }
        if (!failures.isEmpty()) {
            throw new RuntimeException(String.join("; ", failures));
        }
        System.out.println("PASSED (" + bySkia + " non-antialiased shapes by Skia)");
    }

    static long nonAAShapesBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterNonAAShapesBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Every differing pixel must be an edge pixel of the loops' rendering, and at most edgeShare of those may differ. */
    static void compare(String what, Consumer<Graphics2D> drawing, double edgeShare) {
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setColor(Color.BLUE);
            drawing.accept(g);
            g.dispose();
        }
        int differing = 0, interior = 0, edges = 0, maxLevels = 0; int xi = -1, yi = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int l = loops.getRGB(x, y), s = skia.getRGB(x, y);
            boolean edge = (x > 0 && loops.getRGB(x - 1, y) != l) || (x < W - 1 && loops.getRGB(x + 1, y) != l) || (y > 0 && loops.getRGB(x, y - 1) != l) || (y < H - 1 && loops.getRGB(x, y + 1) != l);
            if (edge) edges++;
            if (s != l) {
                differing++;
                int d = 0; for (int shift = 0; shift <= 16; shift += 8) d = Math.max(d, Math.abs(((s >> shift) & 255) - ((l >> shift) & 255)));
                if (d > maxLevels) { maxLevels = d; xi = x; yi = y; }
                if (!edge) interior++;
            }
        }
        System.out.printf("%s: %d differing pixels (%d interior) of %d edge pixels, max %d levels at (%d,%d)%n", what, differing, interior, edges, maxLevels, xi, yi);
        if (interior > 0 || differing > edgeShare * edges) {
            failures.add(what + ": " + differing + " differing pixels (" + interior + " interior) against " + edges + " edge pixels, limit " + (int) (edgeShare * edges));
        }
    }
}
