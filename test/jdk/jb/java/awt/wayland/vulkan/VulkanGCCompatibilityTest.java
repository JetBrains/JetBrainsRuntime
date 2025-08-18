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

import sun.java2d.vulkan.VKGraphicsConfig;
import sun.java2d.vulkan.VKEnv;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.IOException;

/*
 * @test
 * @requires os.family == "linux"
 * @summary Verifies Vulkan graphics config compatibility
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.accelsd=true VulkanGCCompatibilityTest
 */


public class VulkanGCCompatibilityTest {
    final static int W = 600;
    final static int H = 600;

    static boolean compareColors(Color c1, Color c2, double tolerance) {
        return Math.abs(c1.getRed() - c2.getRed()) <= tolerance &&
                Math.abs(c1.getGreen() - c2.getGreen()) <= tolerance &&
                Math.abs(c1.getBlue() - c2.getBlue()) <= tolerance;
    }

    static void validate(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y), true);
        if (!compareColors(actual, expected, 1)) {
            throw new Error("Unexpected color: " + actual + ", expected: " + expected);
        }
    }

    private static void test(VolatileImage image, GraphicsConfiguration config, int expectedValidationResult) throws IOException {
        int validationResult = image.validate(config);
        if (validationResult != expectedValidationResult)
            throw new Error("Unexpected validation result: " + validationResult);
        if (image.contentsLost()) throw new Error("Content lost");
        Graphics g = image.getGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, W/3, H/3);
        g.setColor(Color.GREEN);
        g.fillRect(W/3, H/3, W/3, H/3);
        g.setColor(Color.BLUE);
        g.fillRect((2*W)/3, (2*H)/3, W/3, H/3);
        if (image.contentsLost()) throw new Error("Content lost");
        BufferedImage snapshot = image.getSnapshot();
        ImageIO.write(snapshot, "PNG", new File("snapshot.png"));
        validate(snapshot, W/6, H/6, Color.RED);
        validate(snapshot, W*3/6, H*3/6, Color.GREEN);
        validate(snapshot, W*5/6, H*5/6, Color.BLUE);
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

        final GraphicsConfiguration[] configs =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getConfigurations();
        // Find configs with the same format for different Vulkan devices.
        GraphicsConfiguration configA = null, configB = null;
        for (int i = 0; i < configs.length - 1; i++) {
            for (int j = i + 1; j < configs.length; j++) {
                VKGraphicsConfig a = (VKGraphicsConfig) configs[i], b = (VKGraphicsConfig) configs[j];
                // TODO ensure same format
                if (a.getGPU() == b.getGPU()) continue;
                configA = (GraphicsConfiguration) a;
                configB = (GraphicsConfiguration) b;
                break;
            }
        }
        if (configA == null) {
            System.out.println("No suitable configs found, skipping test. Configs: " + configs.length +
                               ", devices: " + VKEnv.getDevices().count());
            return;
        }

        // Create and paint a new Vulkan image.
        VolatileImage image = configA.createCompatibleVolatileImage(W, H, Transparency.TRANSLUCENT);
        test(image, configA, VolatileImage.IMAGE_RESTORED);
        test(image, configA, VolatileImage.IMAGE_OK);
        // Switch to another device.
        test(image, configB, VolatileImage.IMAGE_RESTORED);
        test(image, configB, VolatileImage.IMAGE_OK);
    }
}
