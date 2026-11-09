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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * @test
 * @summary Texture paints on a Skia raster surface are Skia image shaders: a
 *          TexturePaint over an INT_RGB, INT_ARGB (translucent) or INT_ARGB_PRE
 *          image, nearest and bilinear, under a translation, a scaled anchor,
 *          a rotation and a fractional device scale, with extra alpha, on
 *          antialiased and non-antialiased fills and on strokes, matches the
 *          software loops' TexturePaintContext (texels at integer coordinates,
 *          the pixel's corner sampled, the image repeated; an unpremultiplied
 *          texture filtered unpremultiplied) within the filter's rounding, and
 *          the paint reaches Skia (the counter) instead of the loops' paint
 *          pipes. The other standard image types are converted by the loops'
 *          own blit and sampled alike (nearest for every one, filtered where the
 *          loops' context blends 8-bit ARGB without re-quantising); custom image
 *          types, and a texture whose image is the destination, stay with the loops.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia.raster=true -Djava.awt.headless=true SkiaRasterTexturePaintTest
 */
public class SkiaRasterTexturePaintTest {
    static final int W = 160, H = 120;

    /**
     * The other types whose filtered texture paints Skia draws: those whose loops' context blends unpremultiplied
     * 8-bit ARGB and stores it losslessly (the Int and ByteFilter contexts, and the generic one over 8-bit
     * channels); the generic context re-quantises a blend to 565 / 555, gray (through its colour space) or one
     * bit, which stays with the loops (measured with Skia taking them: 565 / 555 4 levels off, the gray types 18,
     * BYTE_BINARY 122; the five below 0 or 1).
     */
    static final java.util.Set<Integer> FILTERED_BY_SKIA = java.util.Set.of(BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_4BYTE_ABGR_PRE, BufferedImage.TYPE_BYTE_INDEXED);

    public static void main(String[] args) throws Exception {
        BufferedImage rgb = texture(BufferedImage.TYPE_INT_RGB, 7, 5, false);
        BufferedImage argb = texture(BufferedImage.TYPE_INT_ARGB, 6, 9, true);
        BufferedImage pre = texture(BufferedImage.TYPE_INT_ARGB_PRE, 5, 4, true);
        Shape oval = new Ellipse2D.Double(10, 8, 140, 104);
        Shape box = new Rectangle2D.Double(6, 5, 148, 110);

        // nearest under an integer mapping: Java steps the image coordinates exactly, the interior is exact
        compare("INT_RGB nearest, anchor at (3,2), non-AA rect", false, 0, 0, true, g -> {
            g.setPaint(new TexturePaint(rgb, new Rectangle2D.Double(3, 2, 7, 5))); g.fill(box); }, box, false);
        // nearest with half-texel steps: Java's truncated fixed-point steps put a texel boundary that falls on a
        // pixel into the texel before it, except on the first row and column of each of its 32-pixel paint tiles,
        // which start exactly: at most one row and one column in 32 a texel off (measured 3.0 % with the ties of
        // one axis on the tile starts, 6.0 % with both, all on those rows and columns)
        compare("INT_RGB nearest, anchor scaled 2x, AA oval", true, 0, 0.07, true, g -> {
            g.setPaint(new TexturePaint(rgb, new Rectangle2D.Double(-1, 4, 14, 10))); g.fill(oval); }, oval, true);
        compare("INT_ARGB_PRE nearest, extra alpha 0.6 (1 level of rounding), AA oval", true, 1, 0.07, true, g -> {
            g.setComposite(AlphaComposite.SrcOver.derive(0.6f));
            g.setPaint(new TexturePaint(pre, new Rectangle2D.Double(0, 0, 10, 8))); g.fill(oval); }, oval, true);
        // nearest under a fractional mapping: texel boundaries that fall exactly on a pixel corner may round
        // either way (Java steps in 31-bit fixed point, Skia evaluates in float): a few pixels a whole texel off
        // (measured 0.2 %)
        compare("INT_RGB nearest, rotated, device scale 1.25", true, 0, 0.01, true, g -> {
            g.scale(1.25, 1.25); g.rotate(0.3, 64, 48);
            g.setPaint(new TexturePaint(rgb, new Rectangle2D.Double(2, 1, 17.5, 12.5))); g.fill(new RoundRectangle2D.Double(10, 8, 108, 80, 20, 20)); },
            AffineTransform.getScaleInstance(1.25, 1.25), new RoundRectangle2D.Double(10, 8, 108, 80, 20, 20), 0.3, true);
        compare("INT_ARGB nearest (1 level of rounding over translucent texels), rotated, non-AA", false, 1, 0.01, true, g -> {
            g.rotate(0.2, 80, 60);
            g.setPaint(new TexturePaint(argb, new Rectangle2D.Double(4, 4, 6, 9))); g.fill(new Rectangle2D.Double(25, 20, 110, 80)); },
            AffineTransform.getRotateInstance(0.2, 80, 60), new Rectangle2D.Double(25, 20, 110, 80), 0, false);
        // bilinear: Java blends with 12-bit weights, Skia in its raster pipeline's precision (measured 1 level)
        compare("INT_ARGB bilinear (translucent texels), anchor scaled 2.5x", true, 2, 0, true, g -> {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setPaint(new TexturePaint(argb, new Rectangle2D.Double(1, 3, 15, 22.5))); g.fill(oval); }, oval, true);
        compare("INT_RGB filtered by RENDER_QUALITY, rotated", true, 2, 0, true, g -> {
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.rotate(-0.45, 80, 60);
            g.setPaint(new TexturePaint(rgb, new Rectangle2D.Double(0, 0, 21, 15))); g.fill(new Rectangle2D.Double(30, 20, 100, 80)); },
            AffineTransform.getRotateInstance(-0.45, 80, 60), new Rectangle2D.Double(30, 20, 100, 80), 0, true);
        compare("INT_ARGB_PRE bilinear, wide AA stroke", true, 2, 0, true, g -> {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setPaint(new TexturePaint(pre, new Rectangle2D.Double(0, 0, 12.5, 10))); g.draw(new Ellipse2D.Double(25, 20, 110, 80)); },
            new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(new Ellipse2D.Double(25, 20, 110, 80)), true);

        // the other standard types, converted to ARGB by the loops' own blit: nearest copies stored values on both
        // sides; filtering blends unpremultiplied ARGB, as the loops' contexts do for these types
        int[] others = {BufferedImage.TYPE_INT_BGR, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_4BYTE_ABGR_PRE,
                BufferedImage.TYPE_USHORT_565_RGB, BufferedImage.TYPE_USHORT_555_RGB, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY,
                BufferedImage.TYPE_BYTE_BINARY, BufferedImage.TYPE_BYTE_INDEXED};
        for (int type : others) {
            BufferedImage t = texture(type, 7, 5, true);
            int rounding = t.getColorModel().hasAlpha() ? 1 : 0;   // translucent texels: 1 level of the loops' compositing rounding
            compare("type " + type + " nearest, anchor at (3,2), AA oval", true, rounding, 0, true, g -> {
                g.setPaint(new TexturePaint(t, new Rectangle2D.Double(3, 2, 7, 5))); g.fill(oval); }, oval, true);
            compare("type " + type + " nearest, anchor scaled 2x, non-AA rect", false, rounding, 0.07, true, g -> {
                g.setPaint(new TexturePaint(t, new Rectangle2D.Double(-1, 4, 14, 10))); g.fill(box); }, box, false);
            long beforeFiltered = texturesBySkia();
            compare("type " + type + " bilinear, anchor scaled 2.5x", true, 2, 0, FILTERED_BY_SKIA.contains(type), g -> {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setPaint(new TexturePaint(t, new Rectangle2D.Double(1, 3, 17.5, 12.5))); g.fill(oval); }, oval, true);
            if (!FILTERED_BY_SKIA.contains(type) && texturesBySkia() != beforeFiltered) {
                throw new RuntimeException("type " + type + ": a filtered texture reached Skia's raster path (expected: the loops, which re-quantise the blend)");
            }
        }

        long before = texturesBySkia();
        // a custom image type has no native conversion: the loops
        java.awt.image.ColorModel cm = new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
                true, false, java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_USHORT);
        BufferedImage custom = new BufferedImage(cm, cm.createCompatibleWritableRaster(6, 6), false, null);
        custom.createGraphics().drawImage(texture(BufferedImage.TYPE_INT_ARGB, 6, 6, true), 0, 0, null);
        compare("custom (16-bit RGBA) texture: the loops", true, 0, 0, false, g -> {
            g.setPaint(new TexturePaint(custom, new Rectangle2D.Double(0, 0, 6, 6))); g.fill(oval); }, oval, true);
        if (texturesBySkia() != before) {
            throw new RuntimeException("a custom-type texture reached Skia's raster path (expected: the loops)");
        }
        // the destination as its own texture: the loops (the pixels would be read while being written)
        BufferedImage self = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D sg = self.createGraphics();
        sg.setColor(Color.ORANGE); sg.fillRect(0, 0, 8, 8);
        sg.setPaint(new TexturePaint(self, new Rectangle2D.Double(0, 0, 40, 30)));
        sg.fill(new Rectangle2D.Double(50, 50, 60, 40));
        sg.dispose();
        if (texturesBySkia() != before) {
            throw new RuntimeException("a texture painted onto its own image reached Skia's raster path (expected: the loops)");
        }
        System.out.println("PASSED (" + before + " fills with Skia image shaders)");
    }

    /** A texture with a distinct colour per texel; translucent ones vary alpha too (never fully transparent). */
    static BufferedImage texture(int type, int w, int h, boolean translucent) {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int r = 30 + x * 200 / w, gr = 40 + y * 190 / h, b = (x * 7 + y * 13) % 5 * 50;
            int a = translucent ? 70 + ((x + 2 * y) % 4) * 60 : 255;
            img.setRGB(x, y, a << 24 | r << 16 | gr << 8 | b);
        }
        return img;
    }

    static long texturesBySkia() {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("rasterTexturesBySkia");
            return (long) m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    static void compare(String what, boolean aa, int levels, double fraction, boolean bySkia, Consumer<Graphics2D> drawing, Shape coverage, boolean coverageAA) {
        compare(what, aa, levels, fraction, bySkia, drawing, new AffineTransform(), coverage, 0, coverageAA);
    }

    /**
     * Draws on an INT_ARGB_PRE raster surface and on the loops' 3BYTE_BGR reference over grey, and
     * compares the interior — pixels whose 5 x 5 neighbourhood the shape covers fully, so the edge's
     * coverage model stays out: at most {@code fraction} of them may differ by more than {@code levels}.
     */
    static void compare(String what, boolean aa, int levels, double fraction, boolean bySkia, Consumer<Graphics2D> drawing,
                        AffineTransform coverageTransform, Shape coverage, double coverageRotation, boolean coverageAA) {
        long before = texturesBySkia();
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.GRAY); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            drawing.accept(g);
            g.dispose();
        }
        // the shape's full coverage, drawn by the loops in black on white under the same transform
        BufferedImage cov = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D cg = cov.createGraphics();
        cg.setColor(Color.WHITE); cg.fillRect(0, 0, W, H);
        cg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, coverageAA ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        cg.transform(coverageTransform);
        if (coverageRotation != 0) cg.rotate(coverageRotation, 64, 48);
        cg.setColor(Color.BLACK); cg.fill(coverage);
        cg.dispose();
        int interior = 0, off = 0, max = 0, xm = 0, ym = 0;
        for (int y = 2; y < H - 2; y++) for (int x = 2; x < W - 2; x++) {
            boolean inside = true;
            for (int dy = -2; dy <= 2 && inside; dy++) for (int dx = -2; dx <= 2 && inside; dx++) if ((cov.getRGB(x + dx, y + dy) & 0xffffff) != 0) inside = false;
            if (!inside) continue;
            interior++;
            int a = skia.getRGB(x, y), b = loops.getRGB(x, y), d = 0;
            for (int shift = 0; shift <= 16; shift += 8) d = Math.max(d, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
            if (d > levels) off++;
            if (d > max) { max = d; xm = x; ym = y; }
        }
        long reached = texturesBySkia() - before;
        System.out.printf("%s: %d interior pixels, %d beyond %d levels, max %d at (%d,%d); %d fills by Skia%n", what, interior, off, levels, max, xm, ym, reached);
        if (interior < 1000) {
            throw new RuntimeException(what + ": only " + interior + " interior pixels (the coverage shape is wrong)");
        }
        if (off > fraction * interior) {
            throw new RuntimeException(what + ": " + off + " of " + interior + " interior pixels beyond " + levels + " levels (allowed " + (int) (fraction * interior) + "), max " + max + " at (" + xm + "," + ym + ")");
        }
        if (bySkia && reached == 0) {
            throw new RuntimeException(what + ": the texture paint did not reach Skia's raster path (the counter did not move)");
        }
    }
}
