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
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.Consumer;

import sun.java2d.SurfaceData;

/**
 * @test
 * @summary With sun.java2d.skia.raster=true a BufferedImage of the IDE's pixel
 *          formats (INT_ARGB, INT_ARGB_PRE, INT_RGB) gets a Skia raster surface:
 *          its antialiased shapes are drawn by Skia into the image's own pixels
 *          (the DataBuffer sees the ink, nothing is copied), within the curve
 *          coverage tolerance of the software loops and pixel-exact for
 *          axis-aligned rectangles under a shape clip; other image types and
 *          the default stay on the software loops.
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterSurfaceTest raster
 * @run main/othervm -Djava.awt.headless=true SkiaRasterSurfaceTest loops
 */
public class SkiaRasterSurfaceTest {
    static final int W = 120, H = 90;

    public static void main(String[] args) throws Exception {
        boolean raster = args[0].equals("raster");
        String expected = raster ? "SKRasterSurfaceData" : "BufImgSurfaceData";
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB}) {
            String actual = SurfaceData.getPrimarySurfaceData(new BufferedImage(W, H, type)).getClass().getSimpleName();
            if (!actual.equals(expected)) {
                throw new RuntimeException("image type " + type + ": surface " + actual + ", expected " + expected);
            }
        }
        String other = SurfaceData.getPrimarySurfaceData(new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR)).getClass().getSimpleName();
        if (!other.equals("BufImgSurfaceData")) {
            throw new RuntimeException("TYPE_3BYTE_BGR: surface " + other + ", expected the software loops");
        }
        if (!raster) {
            System.out.println("PASSED (the default stays on the software loops)");
            return;
        }

        // the loops' rendering of the same drawing, on a type the raster surface does not take
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            compare("AA oval, opaque", type, Color.WHITE, g -> { g.setColor(new Color(200, 30, 40)); g.fill(new Ellipse2D.Double(10.5, 8.25, 70, 60)); }, 48, 2.0);
            compare("AA path, translucent SrcOver", type, Color.WHITE, g -> {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g.setColor(new Color(20, 80, 200));
                Path2D.Double p = new Path2D.Double(); p.moveTo(5, 85); p.curveTo(30, -20, 90, 120, 115, 10); p.lineTo(115, 85); p.closePath();
                g.fill(p);
            }, 48, 2.0);
            compare("AA rect under a shape clip", type, Color.WHITE, g -> {
                Path2D.Double tri = new Path2D.Double(); tri.moveTo(0, 0); tri.lineTo(W, 0); tri.lineTo(0, H); tri.closePath();
                g.clip(tri);
                g.setColor(Color.BLACK);
                g.fill(new Rectangle2D.Double(3, 4, 100, 70));
            }, 1, 1.0);
            compare("AA stroke", type, Color.WHITE, g -> {
                g.setColor(new Color(0, 120, 0)); g.setStroke(new java.awt.BasicStroke(3f));
                g.draw(new Ellipse2D.Double(15, 12, 80, 60));
            }, 64, 3.0);
        }
        // the ink reaches the image's own pixels: the DataBuffer sees it, nothing is copied
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.RED); g.fill(new Ellipse2D.Double(20, 20, 60, 50)); g.dispose();
        int[] data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        if (data[45 * W + 50] != 0xffff0000 || img.getRGB(50, 45) != 0xffff0000) {
            throw new RuntimeException("the oval's centre is not red in the image's pixels: " + Integer.toHexString(data[45 * W + 50]));
        }
        if (data[2 * W + 2] != 0) {
            throw new RuntimeException("a pixel outside the oval changed: " + Integer.toHexString(data[2 * W + 2]));
        }
        System.out.println("PASSED");
    }

    static void compare(String what, int type, Color background, Consumer<Graphics2D> drawing, int maxLevels, double inkPercent) {
        BufferedImage skia = new BufferedImage(W, H, type), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(background); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
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
        System.out.printf("type %d, %s: max %d levels at (%d,%d), ink %.2f %%%n", type, what, max, x0, y0, ink);
        if (max > maxLevels || ink > inkPercent) {
            throw new RuntimeException("type " + type + ", " + what + ": max " + max + " levels (limit " + maxLevels + "), ink " + ink + " % (limit " + inkPercent + ")");
        }
    }
}
