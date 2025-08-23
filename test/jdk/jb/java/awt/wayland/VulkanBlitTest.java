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
import java.util.Arrays;

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
    static boolean SUPPRESS_ALPHA_VALIDATION = false; // TODO this is temporary.
    final static int W = 256*3;
    final static int H = 600;
    final static int[] FILL_SCANLINE = new int[W];
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
        Arrays.fill(FILL_SCANLINE, 0xffa1babe);
        for (Rectangle clip : CLIP_RECTS) COMPLEX_CLIP.add(new Area(clip));
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
    static void validateClip(BufferedImage image) {
        for (int i = 1; i <= 3; i += 2) {
            for (int j = 1; j <= 9; j += 4) {
                validate(image, W*j/12-2, H*0/10, FILL_COLOR, "clipped area", 1);
                validate(image, W*(j+2)/12+2, H*0/10, FILL_COLOR, "clipped area", 1);
            }
        }
        for (int i = 1; i < 10; i += 2) {
            for (int j = 0; j < 256; j++) {
                validate(image, W*j/256+1, H*i/10+2, FILL_COLOR, "clipped area", 1);
            }
        }
    }
    static void validate(BufferedImage image, boolean hasAlpha) {
        for (int i = 1; i <= 3; i += 2) {
            String atSuffix = i == 1 ? "" : " (under transparent gradient)";
            validate(image, W/6, H*i/10, Color.RED, "red stripe" + atSuffix, 1);
            validate(image, W*3/6, H*i/10, Color.GREEN, "green stripe" + atSuffix, 1);
            validate(image, W*5/6, H*i/10, Color.BLUE, "blue stripe" + atSuffix, 1);
        }
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
        for (int j = 0; j < 256; j++) {
            validate(image, W*j/256+1, H*7/10, new Color(j, j, j), "opaque gradient stripe " + j, 1);
        }
        for (int j = 0; j < 256; j++) {
            if (SUPPRESS_ALPHA_VALIDATION) break;
            Color expected = new Color(j, j, j, hasAlpha ? j : 255);
            validate(image, W*j/256+1, H*9/10, expected, "alpha gradient stripe " + j, 12);
        }
    }

    static void testSurfaceToSwBlit(BufferedImage bi, VolatileImage image, String prefix, boolean hasAlpha) throws IOException {
        // Fill the buffered image with plain color.
        bi.setRGB(0, 0, W, H, FILL_SCANLINE, 0, 0);
        { // Blit into software image.
            Graphics2D g = bi.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageIO.write(bi, "PNG", new File(prefix + "no-clip.png"));
        try {
            validate(bi, hasAlpha);
        } catch (Throwable t) {
            throw new Error(prefix + "no-clip", t);
        }

        // Fill the buffered image with plain color.
        bi.setRGB(0, 0, W, H, FILL_SCANLINE, 0, 0);
        { // Blit again with rect clips.
            Graphics2D g = bi.createGraphics();
            g.setComposite(AlphaComposite.Src);
            for (Rectangle clip : CLIP_RECTS) {
                g.setClip(clip);
                g.drawImage(image, 0, 0, null);
            }
            g.dispose();
        }
        ImageIO.write(bi, "PNG", new File(prefix + "rect-clip.png"));
        try {
            validate(bi, hasAlpha);
            validateClip(bi);
        } catch (Throwable t) {
            throw new Error(prefix + "rect-clip", t);
        }

        // Fill the buffered image with plain color.
        bi.setRGB(0, 0, W, H, FILL_SCANLINE, 0, 0);
        { // Blit again with a complex clip.
            Graphics2D g = bi.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setClip(COMPLEX_CLIP);
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageIO.write(bi, "PNG", new File(prefix + "complex-clip.png"));
        try {
            validate(bi, hasAlpha);
            validateClip(bi);
        } catch (Throwable t) {
            throw new Error(prefix + "complex-clip", t);
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
            validate(bi, hasAlpha);
        } catch (Throwable t) {
            throw new Error(prefix + "snapshot", t);
        }

        // Repeat blit into the same snapshot image.
        testSurfaceToSwBlit(bi, image, prefix + "snapshot-", hasAlpha);

        // Repeat with generic buffered image formats.
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        testSurfaceToSwBlit(bi, image, prefix + "INT_RGB, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        testSurfaceToSwBlit(bi, image, prefix + "INT_ARGB, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        testSurfaceToSwBlit(bi, image, prefix + "INT_ARGB_PRE, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_BGR);
        testSurfaceToSwBlit(bi, image, prefix + "INT_BGR, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        testSurfaceToSwBlit(bi, image, prefix + "3BYTE_BGR, ", false);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR);
        testSurfaceToSwBlit(bi, image, prefix + "4BYTE_ABGR, ", hasAlpha);
        bi = new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR_PRE);
        testSurfaceToSwBlit(bi, image, prefix + "4BYTE_ABGR_PRE, ", hasAlpha);

        // Blit into another Vulkan image.
        hasAlpha = false; // TODO our blit currently ignores alpha, remove when fixed.
        SUPPRESS_ALPHA_VALIDATION = true; // TODO same.
        VolatileImage anotherImage = config.createCompatibleVolatileImage(W, H, transparency);
        if (anotherImage.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new Error("Image validation failed");
        }
        {
            Graphics2D g = anotherImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        if (anotherImage.contentsLost()) throw new Error("Image contents lost");
        // Take a snapshot (blit into Sw) and validate.
        bi = anotherImage.getSnapshot();
        ImageIO.write(bi, "PNG", new File(prefix + "another-snapshot.png"));
        try {
            validate(bi, hasAlpha);
        } catch (Throwable t) {
            throw new Error(prefix + "another-snapshot", t);
        }
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

        // TODO We don't have proper support for alpha rendering into OPAQUE surface atm.
        if (!hasAlpha) SUPPRESS_ALPHA_VALIDATION = true;

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
