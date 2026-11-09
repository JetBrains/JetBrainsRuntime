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

/*
 * @test
 * @summary A TexturePaint on the Skia pipe shows its image from the first use
 *          on: the image's cached texture is uploaded and sampled within one
 *          flush, so the paint must read the texture after the upload (an
 *          unlinked view of the texture sampled it empty: black for an opaque
 *          image, nothing for a translucent one). INT_RGB and INT_ARGB
 *          textures, nearest sampling, anchored half a pixel off so every
 *          sample falls inside a texel; compared with the software loops.
 *          The sampling follows the loops' TexturePaintContext: filtered
 *          textures have their texel centres at integer image coordinates
 *          (half a texel off a GPU sampler's), and a texel boundary that falls
 *          exactly on a pixel goes the way the loops' fixed-point steps send it.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaTexturePaintTest skia
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

public class SkiaTexturePaintTest {

    private static final int W = 96, H = 64;
    private static final int TOLERANCE = 2;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            // a new image per case: its texture is created, uploaded and sampled in the same flush
            for (int uses : new int[] {1, 3}) {
                BufferedImage texture = texture(type);
                Consumer<Graphics2D> scene = g -> {
                    g.setColor(Color.GRAY);
                    g.fillRect(0, 0, W, H);
                    for (int i = 0; i < uses; i++) {
                        g.setPaint(new TexturePaint(texture, new Rectangle2D.Double(0.5, 0.5, 7, 5)));
                        g.fillRect(4 + i, 4, 88 - 2 * i, 56);
                    }
                };
                BufferedImage reference = PipelineComparison.softwareReference(W, H, scene);
                BufferedImage actual = PipelineComparison.rendered(gc, W, H, scene);
                PipelineComparison.Difference d = PipelineComparison.compare(reference, actual, new Rectangle(8, 8, W - 16, H - 16), TOLERANCE);
                String what = (type == BufferedImage.TYPE_INT_RGB ? "INT_RGB" : "INT_ARGB") + ", " + uses + " use(s) in the first flush";
                System.out.println(what + ": " + d.beyond() + " pixels beyond " + TOLERANCE + " levels, max " + d.max());
                if (d.beyond() > 0) {
                    failures.append(what).append(" (").append(d.beyond()).append(" pixels, max ").append(d.max()).append(") ");
                }
            }
        }
        // the sampling rules (a scene drawn twice: the second flush samples the cached texture); measured on Metal-backed
        // Graphite: bilinear and the integer mapping exact, the 2x mapping 2.1 % of the region on the loops' tile seams
        BufferedImage opaque = texture(BufferedImage.TYPE_INT_RGB);
        check(gc, failures, "INT_RGB bilinear, anchor 2.5x", opaque, new Rectangle2D.Double(1, 3, 17.5, 12.5), true, 2, 0);
        check(gc, failures, "INT_RGB nearest, anchor 2x (ties at every second pixel)", opaque, new Rectangle2D.Double(-1, 4, 14, 10), false, 0, LOOPS_TILE_SEAMS);
        check(gc, failures, "INT_RGB nearest, anchor 1x (ties at every pixel)", opaque, new Rectangle2D.Double(3, 2, 7, 5), false, 0, 0);
        if (failures.length() > 0) {
            throw new RuntimeException("Texture paints differ from the software loops: " + failures);
        }
        System.out.println("PASSED");
    }

    /**
     * The loops restart their fixed-point stepping exactly at each 32-pixel paint tile, so with fractional steps
     * the ties on a tile's first row and column go the other way: at most one row and one column in 32.
     */
    private static final double LOOPS_TILE_SEAMS = 2.0 / 32;

    private static void check(GraphicsConfiguration gc, StringBuilder failures, String what, BufferedImage texture,
                              Rectangle2D anchor, boolean bilinear, int tolerance, double fraction) {
        Consumer<Graphics2D> scene = g -> {
            g.setColor(Color.GRAY);
            g.fillRect(0, 0, W, H);
            if (bilinear) {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            g.setPaint(new TexturePaint(texture, anchor));
            g.fillRect(4, 4, 88, 56);
        };
        BufferedImage reference = PipelineComparison.softwareReference(W, H, scene);
        PipelineComparison.rendered(gc, W, H, scene);   // caches the texture
        BufferedImage actual = PipelineComparison.rendered(gc, W, H, scene);
        Rectangle region = new Rectangle(8, 8, W - 16, H - 16);
        PipelineComparison.Difference d = PipelineComparison.compare(reference, actual, region, tolerance);
        System.out.println(what + ": " + d.beyond() + " pixels beyond " + tolerance + " levels, max " + d.max());
        if (d.beyond() > fraction * region.width * region.height) {
            failures.append(what).append(" (").append(d.beyond()).append(" pixels, max ").append(d.max()).append(") ");
        }
    }

    /** Distinct texels; the translucent one varies alpha (never fully transparent), written as Java stores them. */
    private static BufferedImage texture(int type) {
        BufferedImage img = new BufferedImage(7, 5, type);
        boolean translucent = type == BufferedImage.TYPE_INT_ARGB;
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 7; x++) {
                int a = translucent ? 90 + ((x + 2 * y) % 4) * 55 : 255;
                img.setRGB(x, y, a << 24 | (30 + x * 30) << 16 | (40 + y * 45) << 8 | (x * 7 + y * 13) % 5 * 50);
            }
        }
        return img;
    }
}
