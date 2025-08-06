/*
 * Copyright 2024-2025 JetBrains s.r.o.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGPU;
import sun.java2d.vulkan.VKGraphicsConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;

/*
 * @test
 * @requires os.family == "linux"
 * @summary Verifies that Vulkan blit works
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true -Dsun.java2d.vulkan.leOptimizations=true VulkanBlitTest TRANSLUCENT
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true -Dsun.java2d.vulkan.leOptimizations=true VulkanBlitTest OPAQUE
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true -Dsun.java2d.vulkan.leOptimizations=false VulkanBlitTest TRANSLUCENT
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true -Dsun.java2d.vulkan.leOptimizations=false VulkanBlitTest OPAQUE
 */


public class VulkanBlitTest {
    final static int W = 256*3;
    final static int H = 600;
    final static Color FILL_COLOR = new Color(0xffa1babe);
    final static Rectangle[] CLIP_RECTS = new Rectangle[] {
            new Rectangle(W*1/12, H*0/10, W/6, H/10+1),
            new Rectangle(W*5/12, H*0/10, W/6, H/10+1),
            new Rectangle(W*9/12, H*0/10, W/6, H/10+1),
            new Rectangle(W*1/12, H*2/10, W/6, H/10+1),
            new Rectangle(W*5/12, H*2/10, W/6, H/10+1),
            new Rectangle(W*9/12, H*2/10, W/6, H/10+1),
            new Rectangle(0, H*4/10, W, H/10+1),
            new Rectangle(0, H*6/10, W, H/10+1),
            new Rectangle(0, H*8/10, W, H/10+1)
    };
    final static Area COMPLEX_CLIP = new Area();
    static {
        for (Rectangle clip : CLIP_RECTS) COMPLEX_CLIP.add(new Area(clip));
    }

    enum ColorType {
        SYMMETRIC_RGB,
        ASYMMETRIC_RGB,
        GREYSCALE
    }

    static boolean compareColors(Color c1, Color c2, double tolerance) {
        return Math.abs(c1.getRed() - c2.getRed()) <= tolerance &&
                Math.abs(c1.getGreen() - c2.getGreen()) <= tolerance &&
                Math.abs(c1.getBlue() - c2.getBlue()) <= tolerance &&
                c1.getAlpha() == c2.getAlpha();
    }

    static void paint(Image image) {
        Graphics2D g = (Graphics2D) image.getGraphics();
        // RGB stripes
        g.setColor(Color.RED);
        g.fillRect(0, 0, W/3, H);
        g.setColor(Color.GREEN);
        g.fillRect(W/3, 0, W/3, H);
        g.setColor(Color.BLUE);
        g.fillRect(W*2/3, 0, W/3, H);
        // Greyscale gradient (transparent, translucent, opaque, alpha gradient)
        for (int i = 1; i < 5; i++) {
            for (int j = 0; j < 256; j++) {
                if (i == 4) {
                    g.setComposite(AlphaComposite.Src);
                    g.setColor(new Color(j, j, j, j));
                } else g.setColor(new Color(j, j, j, i == 1 ? 0 : i == 2 ? 240 : 255));
                g.fillRect(W*j/256, H*i/5, W/256, H/5);
            }
        }
        g.dispose();
    }

    static String toString(Color c) {
        return "(" + c.getRed() + ", " + c.getGreen() + ", " + c.getBlue() + ", " + c.getAlpha() + ")";
    }
    static void validate(BufferedImage image, int x, int y, Color expected, String at, double tolerance) {
        Color actual = new Color(image.getRGB(x, y), true);
        if (!compareColors(actual, expected, tolerance)) {
            throw new Error("Unexpected color at "+at+": "+toString(actual)+", expected: "+toString(expected));
        }
    }
    static void validateClip(BufferedImage image, double tolerance) {
        for (int i = 1; i <= 3; i += 2) {
            for (int j = 1; j <= 9; j += 4) {
                validate(image, W*j/12-2, H*0/10, FILL_COLOR, "clipped area", tolerance);
                validate(image, W*(j+2)/12+2, H*0/10, FILL_COLOR, "clipped area", tolerance);
            }
        }
        for (int i = 1; i < 10; i += 2) {
            for (int j = 0; j < 256; j++) {
                validate(image, W*j/256+1, H*i/10+2, FILL_COLOR, "clipped area", tolerance);
            }
        }
    }
    static void validate(BufferedImage image, boolean hasAlpha, ColorType colorType, double tolerance) {
        if (colorType != ColorType.GREYSCALE) {
            for (int i = 1; i <= 3; i += 2) {
                String atSuffix = i == 1 ? "" : " (under transparent gradient)";
                validate(image, W/6, H*i/10, Color.RED, "red stripe" + atSuffix, tolerance);
                validate(image, W*3/6, H*i/10, Color.GREEN, "green stripe" + atSuffix, tolerance);
                validate(image, W*5/6, H*i/10, Color.BLUE, "blue stripe" + atSuffix, tolerance);
            }
        }
        if (colorType == ColorType.SYMMETRIC_RGB) {
            for (int j = 0; j < 256; j++) {
                Color actual = new Color(image.getRGB(W*j/256+1, H*5/10), true);
                if (actual.getRed() != actual.getGreen() &&
                        actual.getBlue() != actual.getGreen() &&
                        actual.getRed() != actual.getBlue()) {
                    throw new Error("Unexpected color (all components different) at translucent gradient stripe " +
                            j + ": " + toString(actual));
                } else if (actual.getRed() == actual.getGreen() &&
                        actual.getRed() == actual.getBlue()) {
                    throw new Error("Unexpected color (all components equal) at translucent gradient stripe " +
                            j + ": " + toString(actual));
                }
            }
        }
        for (int j = 0; j < 256; j++) {
            validate(image, W*j/256+1, H*7/10, new Color(j, j, j), "opaque gradient stripe " + j, tolerance);
        }
        for (int j = 0; j < 256; j++) {
            Color expected = new Color(j, j, j, hasAlpha ? j : 255);
            validate(image, W*j/256+1, H*9/10, expected, "alpha gradient stripe " + j, 12);
        }
    }

    static void testBlit(Image src, Image dst, String prefix, boolean hasAlpha) throws IOException {
        testBlit(src, dst, prefix, hasAlpha, ColorType.SYMMETRIC_RGB, 1);
    }
    static void testBlit(Image src, Image dst, String prefix, boolean hasAlpha, ColorType colorType, double tolerance) throws IOException {
        BufferedImage validationImage;

        {
            Graphics2D g = (Graphics2D) dst.getGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(FILL_COLOR);
            g.fillRect(0, 0, W, H);
            for (Rectangle clip : CLIP_RECTS) {
                g.setClip(clip);
                g.drawImage(src, 0, 0, null);
            }
            g.dispose();
        }
        validationImage = dst instanceof BufferedImage ? (BufferedImage) dst : ((VolatileImage) dst).getSnapshot();
        ImageIO.write(validationImage, "PNG", new File(prefix + "rect-clip.png"));
        try {
            validate(validationImage, hasAlpha, colorType, tolerance);
            validateClip(validationImage, tolerance);
        } catch (Throwable t) {
            throw new Error(prefix + "rect-clip", t);
        }

        {
            Graphics2D g = (Graphics2D) dst.getGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(FILL_COLOR);
            g.fillRect(0, 0, W, H);
            g.setClip(COMPLEX_CLIP);
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        validationImage = dst instanceof BufferedImage ? (BufferedImage) dst : ((VolatileImage) dst).getSnapshot();
        ImageIO.write(validationImage, "PNG", new File(prefix + "complex-clip.png"));
        try {
            validate(validationImage, hasAlpha, colorType, tolerance);
            validateClip(validationImage, tolerance);
        } catch (Throwable t) {
            throw new Error(prefix + "complex-clip", t);
        }

        {
            Graphics2D g = (Graphics2D) dst.getGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(FILL_COLOR);
            g.fillRect(0, 0, W, H);
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        validationImage = dst instanceof BufferedImage ? (BufferedImage) dst : ((VolatileImage) dst).getSnapshot();
        ImageIO.write(validationImage, "PNG", new File(prefix + "no-clip.png"));
        try {
            validate(validationImage, hasAlpha, colorType, tolerance);
            try {
                validateClip(validationImage, tolerance);
                throw new Error("Clip validation succeeded");
            } catch (Throwable ignore) {}
        } catch (Throwable t) {
            throw new Error(prefix + "no-clip", t);
        }
    }

    static void test(VKGraphicsConfig vkgc, boolean hasAlpha) throws IOException {
        GraphicsConfiguration config = (GraphicsConfiguration) vkgc;
        int transparency = hasAlpha ? VolatileImage.TRANSLUCENT : VolatileImage.OPAQUE;
        String prefix = vkgc.descriptorString() + (hasAlpha ? ", TRANSLUCENT, " : ", OPAQUE, ");

        // Create and paint a new Vulkan image.
        VolatileImage image = config.createCompatibleVolatileImage(W, H, transparency);
        if (image.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) throw new Error("Image validation failed");
        paint(image);
        if (image.contentsLost()) throw new Error("Image contents lost");
        // Take a snapshot (blit into Sw) and validate.
        BufferedImage bi = image.getSnapshot();
        ImageIO.write(bi, "PNG", new File(prefix + "snapshot.png"));
        try {
            validate(bi, hasAlpha, ColorType.SYMMETRIC_RGB, 1);
        } catch (Throwable t) {
            throw new Error(prefix + "snapshot", t);
        }

        // Create another Vulkan image.
        VolatileImage check = config.createCompatibleVolatileImage(W, H, transparency);
        if (check.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new Error("Image validation failed");
        }

        // Repeat blit to/from the same snapshot image.
        testBlit(image, bi, prefix + "snapshot, surface-sw, ", hasAlpha);
        testBlit(bi, check, prefix + "snapshot, sw-surface, ", hasAlpha);

        // Repeat with generic buffered image formats.
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        testBlit(image, bi, prefix + "INT_RGB, surface-sw, ", false);
        testBlit(bi, check, prefix + "INT_RGB, sw-surface, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        testBlit(image, bi, prefix + "INT_ARGB, surface-sw, ", hasAlpha);
        testBlit(bi, check, prefix + "INT_ARGB, sw-surface, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        testBlit(image, bi, prefix + "INT_ARGB_PRE, surface-sw, ", hasAlpha);
        testBlit(bi, check, prefix + "INT_ARGB_PRE, sw-surface, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_BGR);
        testBlit(image, bi, prefix + "INT_BGR, surface-sw, ", false);
        testBlit(bi, check, prefix + "INT_BGR, sw-surface, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        testBlit(image, bi, prefix + "3BYTE_BGR, surface-sw, ", false);
        testBlit(bi, check, prefix + "3BYTE_BGR, sw-surface, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR);
        testBlit(image, bi, prefix + "4BYTE_ABGR, surface-sw, ", hasAlpha);
        testBlit(bi, check, prefix + "4BYTE_ABGR, sw-surface, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR_PRE);
        testBlit(image, bi, prefix + "4BYTE_ABGR_PRE, surface-sw, ", hasAlpha);
        testBlit(bi, check, prefix + "4BYTE_ABGR_PRE, sw-surface, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_565_RGB);
        testBlit(image, bi, prefix + "TYPE_USHORT_565_RGB, surface-sw, ", false, ColorType.ASYMMETRIC_RGB, 7);
        testBlit(bi, check, prefix + "TYPE_USHORT_565_RGB, sw-surface, ", false, ColorType.ASYMMETRIC_RGB, 7);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_USHORT_555_RGB);
        testBlit(image, bi, prefix + "TYPE_USHORT_555_RGB, surface-sw, ", false, ColorType.SYMMETRIC_RGB, 7);
        testBlit(bi, check, prefix + "TYPE_USHORT_555_RGB, sw-surface, ", false, ColorType.SYMMETRIC_RGB, 7);
        // TODO we don't support direct gray blit atm because of the linear color space of TYPE_BYTE_GRAY
//        bi = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
//        testBlit(image, bi, prefix + "TYPE_BYTE_GRAY, surface-sw, ", false, ColorType.GREYSCALE, 1);
//        testBlit(bi, check, prefix + "TYPE_BYTE_GRAY, sw-surface, ", false, ColorType.GREYSCALE, 1);

        // Blit into another Vulkan image.
        testBlit(image, check, prefix + "surface-surface, ", hasAlpha);

        if (image.contentsLost()) throw new Error("Image contents lost");
        if (check.contentsLost()) throw new Error("Check contents lost");

        // Blit into another Vulkan image with a different alpha type.
        VolatileImage alpha = config.createCompatibleVolatileImage(W, H, 4 - transparency);
        if (alpha.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new Error("Image validation failed");
        }
        testBlit(image, alpha, prefix + "surface-surface, other-alpha, ", false);
        if (image.contentsLost()) throw new Error("Image contents lost");
        if (alpha.contentsLost()) throw new Error("Alpha contents lost");
    }

    public static void main(String[] args) throws IOException {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance()) {
            System.out.println("No WLToolkit, skipping test");
            return;
        }
        if (!VKEnv.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }
        if (!VKEnv.isSurfaceDataAccelerated()) {
            throw new Error("Accelerated surface data not enabled");
        }

        boolean hasAlpha;
        if (args.length > 0 && args[0].equals("TRANSLUCENT")) hasAlpha = true;
        else if (args.length > 0 && args[0].equals("OPAQUE")) hasAlpha = false;
        else throw new Error("Usage: VulkanBlitTest <TRANSLUCENT|OPAQUE>");

        VKEnv.getDevices().flatMap(VKGPU::getOffscreenGraphicsConfigs).forEach(gc -> {
            System.out.println("Testing " + gc);
            try {
                test(gc, hasAlpha);
            } catch (IOException e) {
                throw new Error(e);
            }
        });
    }
}
