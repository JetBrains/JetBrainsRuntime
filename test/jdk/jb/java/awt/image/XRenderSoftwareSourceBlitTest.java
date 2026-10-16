/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

/*
 * @test
 * @key headful
 * @requires os.family == "linux"
 * @summary JBR-5818 software images without a dedicated XRender pipeline should not read the pixmap back from XServer
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.loops
 * @run main/othervm -Dawt.toolkit.name=XToolkit -Dsun.java2d.xrender=true ${test.main.class}
 */

import jtreg.SkippedException;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.Blit;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.ScaledBlit;
import sun.java2d.loops.SurfaceType;
import sun.java2d.loops.TransformBlit;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class XRenderSoftwareSourceBlitTest {
    private static final int W = 40;
    private static final int H = 30;

    private record Source(String name, Supplier<BufferedImage> image) {
    }

    private record Mode(String name, int tolerance, Consumer<Graphics2D> setup, DrawCall draw) {
    }

    private interface DrawCall {
        void draw(Graphics2D g, Image img);
    }

    private static final Mode[] MODES = {
            new Mode("SrcOver", 0, g -> {
            }, (g, img) -> g.drawImage(img, 3, 5, null)),
            // tolerance due to rounding differences
            new Mode("SrcOver 50%", 1, g -> g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)),
                    (g, img) -> g.drawImage(img, 3, 5, null)),
            new Mode("sub-rectangle", 0, g -> {
            },
                    (g, img) -> g.drawImage(img, 3, 5, 3 + 25, 5 + 18, 7, 4, 7 + 25, 4 + 18, null)),
            new Mode("scaled x2", 0, g -> {
            },
                    (g, img) -> g.drawImage(img, 3, 5, W * 2, H * 2, null)),
            new Mode("rotated 90", 0, g -> g.rotate(Math.PI / 2, 40, 30),
                    (g, img) -> g.drawImage(img, 10, 10, null)),
    };

    public static void main() {
        GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!config.getClass().getName().equals("sun.java2d.xr.XRGraphicsConfig")) {
            throw new SkippedException("XRender pipeline is not used: " + config.getClass().getName());
        }

        List<String> failures = new ArrayList<>();
        for (Source source : sources()) {
            checkLoops(config, source, failures);
            for (Mode mode : MODES) {
                checkRendering(config, source, mode, failures);
            }
        }

        if (!failures.isEmpty()) {
            failures.forEach(System.err::println);
            throw new RuntimeException("Test failed");
        }
        System.out.println("Test PASSED");
    }

    private static List<Source> sources() {
        List<Source> sources = new ArrayList<>();
        for (boolean opaque : new boolean[]{false, true}) {
            String suffix = opaque ? " opaque" : " translucent";
            sources.add(new Source("BGRA bytes (IntelliJ SVG icon cache)" + suffix, () -> fill(bgraImage(), opaque)));
            sources.add(new Source("TYPE_4BYTE_ABGR" + suffix, () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR), opaque)));
            sources.add(new Source("TYPE_4BYTE_ABGR_PRE" + suffix, () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR_PRE), opaque)));
            sources.add(new Source("TYPE_INT_ARGB_PRE" + suffix, () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), opaque)));
        }
        sources.add(new Source("TYPE_BYTE_GRAY", () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY), true)));
        sources.add(new Source("TYPE_USHORT_GRAY", () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_USHORT_GRAY), true)));
        sources.add(new Source("TYPE_BYTE_BINARY", () -> fill(new BufferedImage(W, H, BufferedImage.TYPE_BYTE_BINARY), true)));
        return sources;
    }

    // com.intellij.ui.svg.SvgCacheManager uses non-premultiplied BGRA
    private static BufferedImage bgraImage() {
        ComponentColorModel componentColorModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB),
                true,
                false,
                Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE
        );
        WritableRaster raster = Raster.createInterleavedRaster(
                new DataBufferByte(W * H * 4),
                W,
                H,
                W * 4,
                4,
                new int[]{2, 1, 0, 3},
                new Point(0, 0)
        );
        return new BufferedImage(componentColorModel, raster, false, null);
    }

    private static BufferedImage fill(BufferedImage img, boolean opaque) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Src);
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                int alpha = opaque ? 255 : x * 255 / (W - 1);
                g.setColor(new Color((x * 7) & 0xff, (y * 11) & 0xff, ((x + y) * 5) & 0xff, alpha));
                g.fillRect(x, y, 1, 1);
            }
        }
        g.dispose();
        return img;
    }

    private static void checkLoops(GraphicsConfiguration config, Source source, List<String> failures) {
        SurfaceType srcType = SurfaceData.getPrimarySurfaceData(source.image().get()).getSurfaceType();
        for (int transparency : new int[]{Transparency.OPAQUE, Transparency.TRANSLUCENT}) {
            VolatileImage target = config.createCompatibleVolatileImage(W, H, transparency);
            Graphics2D g = target.createGraphics();
            SurfaceType dstType = ((SunGraphics2D) g).getSurfaceData().getSurfaceType();
            g.dispose();
            for (CompositeType comp : new CompositeType[]{CompositeType.SrcNoEa, CompositeType.SrcOverNoEa}) {
                String name = source.name() + " -> " + dstType + " " + comp;
                checkXRenderLoop("Blit " + name, Blit.getFromCache(srcType, comp, dstType), failures);
                checkXRenderLoop("ScaledBlit " + name, ScaledBlit.getFromCache(srcType, comp, dstType), failures);
                checkXRenderLoop("TransformBlit " + name, TransformBlit.getFromCache(srcType, comp, dstType), failures);
            }
            target.flush();
        }
    }

    private static void checkXRenderLoop(String name, GraphicsPrimitive loop, List<String> failures) {
        if (loop == null || !loop.getClass().getPackageName().equals("sun.java2d.xr")) {
            failures.add(name + ": expected an XRender loop, got " + loop);
        }
    }

    private static void checkRendering(GraphicsConfiguration config, Source source, Mode mode, List<String> failures) {
        BufferedImage image = source.image().get();
        BufferedImage reference = new BufferedImage(W * 3, H * 3, BufferedImage.TYPE_INT_RGB);
        draw(reference, image, mode);
        int[] expected = reference.getRGB(0, 0, reference.getWidth(), reference.getHeight(), null, 0, reference.getWidth());

        VolatileImage target = config.createCompatibleVolatileImage(reference.getWidth(), reference.getHeight(), Transparency.OPAQUE);
        // first draws directly, second uses cache
        for (int pass = 1; pass <= 2; ++pass) {
            target.validate(config);
            draw(target, image, mode);

            BufferedImage snapshot = target.getSnapshot();
            int[] actual = snapshot.getRGB(0, 0, snapshot.getWidth(), snapshot.getHeight(), null, 0, snapshot.getWidth());
            int diff = maxChannelDiff(expected, actual);
            if (diff > mode.tolerance) {
                failures.add(source.name() + ", " + mode.name() + (pass == 1 ? ", uncached" : ", cached") + ": pixels differ from software rendering by up to " + diff);
            }
        }
        target.flush();
    }

    private static void draw(Image dst, BufferedImage src, Mode mode) {
        Graphics2D g = (Graphics2D) dst.getGraphics();
        g.setColor(new Color(40, 90, 160));
        g.fillRect(0, 0, W * 3, H * 3);
        mode.setup().accept(g);
        mode.draw().draw(g, src);
        g.dispose();
    }

    private static int maxChannelDiff(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; ++i) {
            for (int shift = 0; shift < 24; shift += 8) {
                max = Math.max(max, Math.abs(((a[i] >> shift) & 0xff) - ((b[i] >> shift) & 0xff)));
            }
        }
        return max;
    }
}
