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
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary Gradient paints on a Skia raster surface are Skia shaders: a
 *          GradientPaint (cyclic and not), a LinearGradientPaint with several
 *          stops in each cycle method and with its own transform, and a
 *          RadialGradientPaint with a focus, filling antialiased shapes under
 *          a rotation, match the software loops' gradients within the
 *          interpolation and curve-coverage tolerance, and the paint reaches
 *          Skia (the counter) instead of the loops' paint pipes.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterPaintTest
 */
public class SkiaRasterPaintTest {
    static final int W = 160, H = 120;

    public static void main(String[] args) throws Exception {
        Color[] three = {new Color(220, 40, 40), new Color(40, 200, 60, 128), new Color(30, 60, 220)};
        float[] stops = {0f, 0.35f, 1f};
        compare("GradientPaint, acyclic", g -> { g.setPaint(new GradientPaint(20, 20, Color.RED, 140, 100, Color.BLUE, false)); g.fill(new Ellipse2D.Double(10, 10, 140, 100)); });
        compare("GradientPaint, cyclic", g -> { g.setPaint(new GradientPaint(40, 60, Color.YELLOW, 70, 60, new Color(0, 0, 0, 0), true)); g.fill(new Rectangle2D.Double(5.5, 5.5, 149, 109)); });
        compare("LinearGradientPaint REFLECT, 3 stops, rotated", g -> { g.rotate(0.4, 80, 60);
            g.setPaint(new LinearGradientPaint(new Point2D.Float(40, 30), new Point2D.Float(100, 90), stops, three, MultipleGradientPaint.CycleMethod.REFLECT));
            g.fill(new RoundRectangle2D.Double(20, 15, 120, 90, 24, 24)); });
        compare("LinearGradientPaint REPEAT with its own transform", g -> {
            g.setPaint(new LinearGradientPaint(new Point2D.Float(0, 0), new Point2D.Float(30, 0), stops, three, MultipleGradientPaint.CycleMethod.REPEAT,
                    MultipleGradientPaint.ColorSpaceType.SRGB, AffineTransform.getRotateInstance(0.7)));
            g.fill(new Ellipse2D.Double(10, 10, 140, 100)); });
        compare("RadialGradientPaint with focus, NO_CYCLE", g -> {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(80, 60), 55f, new Point2D.Float(60, 45), stops, three, MultipleGradientPaint.CycleMethod.NO_CYCLE));
            g.fill(new Ellipse2D.Double(15, 5, 130, 110)); });
        long bySkia = paintsBySkia();
        if (bySkia == 0) {
            throw new RuntimeException("no gradient paint reached Skia's raster path (the counter is 0)");
        }
        System.out.println("PASSED (" + bySkia + " fills with Skia shaders)");
    }

    static long paintsBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterPaintsBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    static void compare(String what, Consumer<Graphics2D> drawing) {
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawing.accept(g);
            g.dispose();
        }
        // interior pixels — no unpainted (white) pixel within two pixels, so both sides are fully covered — test the
        // gradient itself: ≤ 12 levels (measured 2); the shape's edge pixels add the curve coverage model: ≤ 56 (51)
        int maxInterior = 0, maxEdge = 0; int xi = 0, yi = 0;
        for (int y = 2; y < H - 2; y++) for (int x = 2; x < W - 2; x++) {
            int a = skia.getRGB(x, y), b = loops.getRGB(x, y);
            int d = 0;
            for (int shift = 0; shift <= 16; shift += 8) d = Math.max(d, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
            boolean interior = true;
            for (int dy = -2; dy <= 2 && interior; dy++) for (int dx = -2; dx <= 2 && interior; dx++) if (loops.getRGB(x + dx, y + dy) == 0xffffffff) interior = false;
            if (interior) { if (d > maxInterior) { maxInterior = d; xi = x; yi = y; } } else maxEdge = Math.max(maxEdge, d);
        }
        System.out.printf("%s: interior max %d levels at (%d,%d), edge max %d%n", what, maxInterior, xi, yi, maxEdge);
        if (maxInterior > 12 || maxEdge > 56) {
            throw new RuntimeException(what + ": interior max " + maxInterior + " (limit 12), edge max " + maxEdge + " (limit 56)");
        }
    }
}
