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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary Strokes on a Skia raster surface are Skia's own stroker's by
 *          default (sun.java2d.skia.strokes, the GPU pipe's rule): the stroke
 *          geometry is built natively from the user-space path, width, cap,
 *          join and miter limit under the transform, and the result stays
 *          within the join and coverage tolerance of the software loops'
 *          Marlin-stroked rendering. Dashed strokes and the comparison mode
 *          strokes=java keep Java's stroker outline, which then matches the
 *          loops within the coverage model alone.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterStrokeTest skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Dsun.java2d.skia.strokes=java -Djava.awt.headless=true SkiaRasterStrokeTest java
 */
public class SkiaRasterStrokeTest {
    static final int W = 160, H = 120;

    public static void main(String[] args) throws Exception {
        boolean skia = args[0].equals("skia");
        // Skia's stroker against Marlin's: the two offset-curve approximations put a stroke edge up to half a
        // pixel apart at a curve or a joint, which on a 3-px stroke is a pixel at about 110 levels (93 measured at
        // 1x), with the ink equal within a few tenths of a percent. Java's outline filled by Skia against Marlin stroking
        // the path directly differs by the coverage model and the stroker's subdivision of the outline: 62 measured.
        int joinLimit = skia ? 112 : 64;
        compare("round-joined cubic, width 3", g -> { g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(new Color(0, 90, 160));
            Path2D.Double p = new Path2D.Double(); p.moveTo(10, 100); p.curveTo(40, -30, 90, 150, 150, 20); p.lineTo(120, 110); g.draw(p); }, joinLimit, 3.0);
        compare("mitered rectangle, width 5", g -> { g.setStroke(new BasicStroke(5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)); g.setColor(Color.DARK_GRAY);
            g.draw(new Rectangle2D.Double(20.5, 15.5, 100, 70)); }, joinLimit, 3.0);
        compare("oval under a rotation, width 2", g -> { g.transform(AffineTransform.getRotateInstance(0.3, 80, 60)); g.setStroke(new BasicStroke(2f)); g.setColor(new Color(160, 20, 20));
            g.draw(new Ellipse2D.Double(30, 25, 100, 60)); }, joinLimit, 3.0);
        compare("dashed line, width 2", g -> { g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6, 4}, 0)); g.setColor(Color.BLACK);
            g.draw(new java.awt.geom.Line2D.Double(10, 60, 150, 70)); }, 64, 3.0);   // dashes: Java's stroker in both modes
        compare("thin stroke under a scale", g -> { g.scale(2, 2); g.setColor(Color.BLACK); g.draw(new Ellipse2D.Double(10, 8, 60, 45)); }, joinLimit, 3.0);
        // Normalization is Marlin's for antialiased strokes: coordinates snap to quarter pixels (floor(v + 0.25) + 0.25),
        // not to pixel centres, so a thin line at an integer coordinate covers two rows at 75 / 25 % like the loops' —
        // straight axis-aligned lines leave no room for stroker or coverage differences
        compare("thin axis-aligned lines at integer coordinates", g -> { g.setColor(Color.BLACK);
            g.draw(new java.awt.geom.Line2D.Double(10, 30, 150, 30)); g.draw(new java.awt.geom.Line2D.Double(80, 10, 80, 110));
            g.setStroke(new BasicStroke(3f)); g.draw(new java.awt.geom.Line2D.Double(10, 70.5, 150, 70.5)); }, 8, 0.5);

        long bySkia = strokesBySkia();
        if (skia && bySkia == 0) {
            throw new RuntimeException("Skia's stroker was not used for any stroke (the counter is " + bySkia + ")");
        }
        if (!skia && bySkia != 0) {
            throw new RuntimeException("strokes=java, yet Skia's stroker was used " + bySkia + " times");
        }
        System.out.println("PASSED (" + bySkia + " strokes by Skia)");
    }

    static long strokesBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterStrokesBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;   // no such counter yet: no stroke went to Skia
        }
    }

    static void compare(String what, Consumer<Graphics2D> drawing, int maxLevels, double inkPercent) {
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawing.accept(g);
            g.dispose();
        }
        int max = 0; long inkA = 0, inkB = 0; int x0 = 0, y0 = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int a = skia.getRGB(x, y), b = loops.getRGB(x, y);
            for (int shift = 0; shift <= 16; shift += 8) {
                int d = Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
                if (d > max) { max = d; x0 = x; y0 = y; }
                inkA += 255 - ((a >> shift) & 255); inkB += 255 - ((b >> shift) & 255);
            }
        }
        double ink = inkB == 0 ? 0 : 100.0 * Math.abs(inkA - inkB) / inkB;
        System.out.printf("%s: max %d levels at (%d,%d), ink %.2f %%%n", what, max, x0, y0, ink);
        if (max > maxLevels || ink > inkPercent) {
            throw new RuntimeException(what + ": max " + max + " levels (limit " + maxLevels + "), ink " + ink + " % (limit " + inkPercent + ")");
        }
    }
}
