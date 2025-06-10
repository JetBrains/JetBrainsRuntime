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
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/*
 * @test
 * @summary Verifies composites in opaque and translucent modes.
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=true VulkanCompositeTest TRANSLUCENT
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=true VulkanCompositeTest OPAQUE
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=false VulkanCompositeTest TRANSLUCENT
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=false VulkanCompositeTest OPAQUE
 */


public class VulkanCompositeTest {
    final static int W = 1;
    final static int H = 1;

    private static class FileLogger {
        private final File file;
        private final String prefix;
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        private FileLogger(File file, String prefix) {
            this.file = file;
            this.prefix = prefix;
//            clearFile();
        }

        void log(String msg) {
            if (file != null) {
                try (var writer = new java.io.BufferedWriter(new java.io.FileWriter(file, true))) {
                    var time = java.time.LocalTime.now();
                    writer.write(formatter.format(time) + ": " + prefix + msg + "\n");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                System.out.println(prefix + msg);
            }
        }

//        void clearFile() {
//            if (file != null) {
//                try {
//                    file.delete();
//                    file.createNewFile();
//                } catch (IOException e) {
//                    throw new Error(e);
//                }
//            } else {
//                System.out.println("Clearing log file");
//            }
//        }
    }

    static FileLogger logger = new FileLogger(new File("vkCompositeTest.log"), "VKCompositeTest: ");

    static Color argb(int argb) { return new Color(argb, true); }
    static boolean compareColors(Color c1, Color c2, double tolerance) {
        return Math.abs(c1.getRed() - c2.getRed()) <= tolerance &&
                Math.abs(c1.getGreen() - c2.getGreen()) <= tolerance &&
                Math.abs(c1.getBlue() - c2.getBlue()) <= tolerance &&
                c1.getAlpha() == c2.getAlpha();
    }

    static void paint(VolatileImage image, GraphicsConfiguration config, Composite composite, Color fill) {
        if (image.validate(config) != VolatileImage.IMAGE_OK) throw new Error("Image validation failed");
        Graphics2D g = (Graphics2D) image.getGraphics();
        g.setComposite(composite);
        g.setColor(fill);
        g.fillRect(0, 0, W, H);
        g.dispose();
        if (image.contentsLost()) throw new Error("Image contents lost");
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
    static void validate(VolatileImage image, String name, Color expected) throws IOException {
        BufferedImage bi = image.getSnapshot();
        ImageIO.write(bi, "PNG", new File(name + ".png"));
        validate(bi, 0, 0, expected, name, 1);
    }

    static void test(VKGraphicsConfig vkgc, boolean hasAlpha) throws IOException {
        GraphicsConfiguration config = (GraphicsConfiguration) vkgc;
        int transparency = hasAlpha ? VolatileImage.TRANSLUCENT : VolatileImage.OPAQUE;
        String prefix = vkgc.descriptorString() + (hasAlpha ? ", TRANSLUCENT, " : ", OPAQUE, ");
        if (prefix.contains("_PACK32")) {
            System.out.println("Skipping " + prefix + "due to VK_IMAGE_CREATE_PACKED_32BIT_STORAGE_BIT_KHR not being supported");
            return;
        }

        // Create a new Vulkan image.
        VolatileImage image = config.createCompatibleVolatileImage(W, H, transparency);
        if (image.validate(config) == VolatileImage.IMAGE_INCOMPATIBLE) throw new Error("Image validation failed");
        // Paint with a non-black, transparent color.
        // On a translucent surface this should result in transparent black due to pre-multiplied alpha.
        // On an opaque surface it should discard alpha and leave the same, but opaque color.
        paint(image, config, AlphaComposite.Src, argb(0x00ab00ba));
        validate(image, prefix + "SRC-TRANSPARENT", hasAlpha ? argb(0x0) : argb(0xffab00ba));
        System.out.println("OK: SRC-TRANSPARENT");
        // Paint with semi-transparent colors.
        paint(image, config, AlphaComposite.Src, argb(0x80ff0000));
        validate(image, prefix + "SRC-SEMI-TRANSPARENT", hasAlpha ? argb(0x80ff0000) : argb(0xffff0000));
        System.out.println("OK: SRC-SEMI-TRANSPARENT");

        paint(image, config, AlphaComposite.SrcOver, argb(0x8000ff00));
        validate(image, prefix + "SRC-OVER", hasAlpha ? argb(0xc055aa00) : argb(0xff7f8000));
        System.out.println("OK: SRC-OVER");
    }

    public static void main(String[] args) throws IOException {
        logger.log("============================== Start testing ==============================");
        logger.log("Args: " + Arrays.toString( args));

        System.out.println("My PID: " + ProcessHandle.current().pid());
        if (!VKEnv.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }

        boolean hasAlpha;
        if (args.length > 0 && args[0].equals("TRANSLUCENT")) hasAlpha = true;
        else if (args.length > 0 && args[0].equals("OPAQUE")) hasAlpha = false;
        else throw new Error("Usage: VulkanBlitTest <TRANSLUCENT|OPAQUE>");

        VKEnv.getDevices().flatMap(VKGPU::getOffscreenGraphicsConfigs).forEach(gc -> {
            logger.log("Testing: " + gc.toString());
            try {
                test(gc, hasAlpha);
            } catch (IOException e) {
                throw new Error(e);
            }
        });
    }
}
