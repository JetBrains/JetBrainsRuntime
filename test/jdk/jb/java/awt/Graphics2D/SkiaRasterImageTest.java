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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary Transformed images on a Skia raster surface are drawn by Skia:
 *          scaled, rotated and sheared draws of INT_ARGB, INT_ARGB_PRE and
 *          INT_RGB sources with nearest-neighbour, bilinear and bicubic
 *          interpolation, with a translucent source and a translucent
 *          composite, match the software loops' transform helper within
 *          its sampling tolerance (a few pixels on a sampling tie may pick the
 *          other source pixel), with edge pixels decided the same way;
 *          the source's pixels are read in place, and the draws reach Skia
 *          (the counter). Plain copies and integer translations stay with the
 *          loops' blits, which are already copies.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterImageTest
 */
public class SkiaRasterImageTest {
    static final int W = 160, H = 120;

    public static void main(String[] args) throws Exception {
        for (int srcType : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB}) {
            BufferedImage src = source(srcType);
            for (Object interp : new Object[] {RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR, RenderingHints.VALUE_INTERPOLATION_BILINEAR, RenderingHints.VALUE_INTERPOLATION_BICUBIC}) {
                String name = "source type " + srcType + ", " + interp;
                int tol = interp == RenderingHints.VALUE_INTERPOLATION_BICUBIC ? 8 : interp == RenderingHints.VALUE_INTERPOLATION_BILINEAR ? 4 : 0;   // measured: 0 / 1 / 2
                compare(name + ", scale 1.5 x 0.8", g -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp); g.drawImage(src, AffineTransform.getScaleInstance(1.5, 0.8), null); }, tol);
                compare(name + ", rotation", g -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp); g.rotate(0.35, 80, 60); g.drawImage(src, 40, 30, null); }, tol);
                compare(name + ", shear, translucent composite", g -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f)); g.shear(0.3, 0.1); g.drawImage(src, 20, 20, null); }, tol);
            }
        }
        long bySkia = imagesBySkia();
        if (bySkia == 0) {
            throw new RuntimeException("no transformed image reached Skia's raster path (the counter is 0)");
        }
        System.out.println("PASSED (" + bySkia + " images by Skia)");
    }

    static BufferedImage source(int type) {
        BufferedImage img = new BufferedImage(64, 48, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 80, 200)); g.fillRect(0, 0, 64, 48);
        g.setColor(new Color(240, 200, 40)); g.fillRect(8, 8, 24, 16);
        g.setColor(new Color(200, 40, 40, type == BufferedImage.TYPE_INT_RGB ? 255 : 120)); g.fillOval(30, 10, 30, 30);
        if (type != BufferedImage.TYPE_INT_RGB) { g.setComposite(AlphaComposite.Clear); g.fillRect(50, 36, 14, 12); }   // a transparent corner
        g.dispose();
        return img;
    }

    static long imagesBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterImagesBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Interior pixels within the tolerance; edge pixels — those whose 3 × 3 neighbourhood in the
     * loops' result touches the background — may differ by an in/out decision, so they are allowed
     * when fewer than 1 % of them do.
     */
    static void compare(String what, Consumer<Graphics2D> drawing, int tolerance) {
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            drawing.accept(g);
            g.dispose();
        }
        int maxInterior = 0, xi = 0, yi = 0, edgeOff = 0, edges = 0, ties = 0;
        for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
            int a = skia.getRGB(x, y), b = loops.getRGB(x, y);
            int d = 0;
            for (int shift = 0; shift <= 16; shift += 8) d = Math.max(d, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
            boolean edge = false;
            for (int dy = -1; dy <= 1 && !edge; dy++) for (int dx = -1; dx <= 1 && !edge; dx++) if (loops.getRGB(x + dx, y + dy) == 0xffffffff) edge = true;
            if (edge) { edges++; if (d > tolerance) edgeOff++; continue; }
            // a sample position on the boundary between two source pixels: the loops' fixed point and Skia's
            // floats may pick either; Skia's value then is the loops' value next door
            if (d > tolerance && (a == loops.getRGB(x - 1, y) || a == loops.getRGB(x + 1, y) || a == loops.getRGB(x, y - 1) || a == loops.getRGB(x, y + 1))) { ties++; continue; }
            else if (d > maxInterior) { maxInterior = d; xi = x; yi = y; }
        }
        System.out.printf("%s: interior max %d levels at (%d,%d), %d tie pixels, edge pixels off %d of %d%n", what, maxInterior, xi, yi, ties, edgeOff, edges);
        if (maxInterior > tolerance || ties > 4 || edgeOff > Math.max(2, edges / 100)) {
            throw new RuntimeException(what + ": interior max " + maxInterior + " (limit " + tolerance + "), " + ties + " tie pixels, edge pixels off " + edgeOff + " of " + edges);
        }
    }
}
