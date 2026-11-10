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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * @test
 * @summary On a Skia raster surface without alpha (INT_RGB), a composite whose result's alpha can fall below 1 is
 *          stored as Java stores it on a destination without alpha: the colour unpremultiplied, the alpha dropped
 *          (black where it is 0) — Clear, Src, SrcIn, DstIn, SrcOut, DstOut, DstAtop and Xor with a translucent
 *          colour: inside the shapes against the software loops on 3BYTE_BGR (no Skia surface takes it), and every
 *          pixel against the same operation on an INT_ARGB_PRE raster surface unpremultiplied (Skia's own geometry
 *          and coverage, Java's store rule): non-antialiased and antialiased fills, a rotated image, text in a Skia
 *          strike. Skia kept the premultiplied colour (a 0x90-alpha 0x8020a0 under Src landed as 0x48125a, up to
 *          135 levels off). SrcOver as the control.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaRasterOpaqueRulesTest
 */
public class SkiaRasterOpaqueRulesTest {
    static final int W = 120, H = 80;
    static final String[] NAMES = {"Clear", "Src", "SrcIn", "DstIn", "SrcOut", "DstOut", "DstAtop", "Xor", "SrcOver"};
    static final int[] RULES = {AlphaComposite.CLEAR, AlphaComposite.SRC, AlphaComposite.SRC_IN, AlphaComposite.DST_IN,
            AlphaComposite.SRC_OUT, AlphaComposite.DST_OUT, AlphaComposite.DST_ATOP, AlphaComposite.XOR, AlphaComposite.SRC_OVER};
    static final Color COLOUR = new Color(0x80, 0x20, 0xa0, 0x90);
    record Shape(String name, Consumer<Graphics2D> draw, boolean edges) {}

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 30; x++) image.setRGB(x, y, (0x40 + 6 * x) << 24 | 0x2060c0 + (y << 3));
        Shape[] shapes = {
            new Shape("a non-antialiased ellipse", g -> g.fill(new Ellipse2D.Float(10, 10, 60, 50)), false),
            new Shape("an antialiased ellipse", g -> {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.fill(new Ellipse2D.Float(10.3f, 10.6f, 60, 50));
            }, true),
            new Shape("a rotated image", g -> g.drawImage(image, AffineTransform.getRotateInstance(0.3, 40, 30), null), true),
            new Shape("text", g -> {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
                g.drawString("Hm", 10, 55);
            }, true),
        };
        StringBuilder failures = new StringBuilder();
        long before = rasterOperations();
        for (Shape shape : shapes) {
            StringBuilder line = new StringBuilder(shape.name + ":");
            for (int k = 0; k < RULES.length; k++) {
                AlphaComposite comp = AlphaComposite.getInstance(RULES[k]);
                BufferedImage raster = draw(BufferedImage.TYPE_INT_RGB, shape, comp), loops = draw(BufferedImage.TYPE_3BYTE_BGR, shape, comp);
                // SrcIn on an opaque destination is Src; and SunGraphics2D classes SrcIn COMP_ISCOPY on an opaque surface but
                // COMP_ALPHA on a translucent one, whose non-antialiased fills take another scan conversion (§18 Q24)
                BufferedImage premultiplied = draw(BufferedImage.TYPE_INT_ARGB_PRE, shape,
                        RULES[k] == AlphaComposite.SRC_IN ? AlphaComposite.getInstance(AlphaComposite.SRC) : comp);
                // the text loops' Src and Clear are their own (ledger §18a T18): the interior check leaves text's out
                boolean loopsRule = !(shape.name.equals("text") && (RULES[k] == AlphaComposite.CLEAR || RULES[k] == AlphaComposite.SRC));
                int inside = 0, store = 0;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    if (loopsRule && interior(loops, x, y)) inside = Math.max(inside, difference(raster.getRGB(x, y), loops.getRGB(x, y)));
                    store = Math.max(store, difference(raster.getRGB(x, y), stored(premultiplied.getRGB(x, y))));
                }
                line.append(' ').append(NAMES[k]).append(' ').append(inside).append('/').append(store);
                if (inside > 2 || store > 2) {
                    failures.append(shape.name).append(", ").append(NAMES[k]).append(": ").append(inside).append(" levels from the loops inside, ")
                            .append(store).append(" from Java's store of the premultiplied result; ");
                }
            }
            System.out.println(line + "   (levels from the loops inside / from Java's store)");
        }
        if (rasterOperations() == before) {
            throw new RuntimeException("nothing drawn by the raster surface: the test would prove nothing");
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static BufferedImage draw(int type, Shape shape, AlphaComposite comp) {
        BufferedImage image = new BufferedImage(W, H, type);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xf0e8d0));
        g.fillRect(0, 0, W, H);
        g.setColor(COLOUR);
        g.setComposite(comp);
        shape.draw.accept(g);
        g.dispose();
        return image;
    }

    /** Java's store on a destination without alpha: the premultiplied result divided by its alpha, black at 0. */
    static int stored(int argb) {
        int a = argb >>> 24;
        if (a == 0) return 0xff000000;
        return argb | 0xff000000;   // getRGB already returns the unpremultiplied colour of an INT_ARGB_PRE pixel
    }

    static int difference(int p, int q) {
        int d = 0;
        for (int s = 0; s < 24; s += 8) d = Math.max(d, Math.abs(((p >> s) & 0xff) - ((q >> s) & 0xff)));
        return d;
    }

    /** A pixel whose eight neighbours the loops coloured alike: inside a shape or outside every shape, not at an edge. */
    static boolean interior(BufferedImage image, int x, int y) {
        if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) return false;
        int c = image.getRGB(x, y);
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) if (image.getRGB(x + dx, y + dy) != c) return false;
        return true;
    }

    static long rasterOperations() throws Exception {
        Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
        return (long) env.getMethod("rasterGlyphsBySkia").invoke(null) + (long) env.getMethod("rasterImagesBySkia").invoke(null)
                + (long) env.getMethod("rasterNonAAShapesBySkia").invoke(null);
    }
}
