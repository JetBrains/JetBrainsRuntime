/*
 * Copyright 2026 JetBrains s.r.o.
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

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/*
 * @test
 * @summary Validating a Vulkan-accelerated VolatileImage against a non-Vulkan GraphicsConfiguration
 * @requires os.family == "linux"
 * @modules java.desktop/sun.java2d.vulkan:+open
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True VulkanBufImgGCValidateTest
 */

public class VulkanBufImgGCValidateTest {
    static final int W = 200;
    static final int H = 200;

    public static void main(String[] args) {
        if (GraphicsEnvironment.getLocalGraphicsEnvironment().isHeadlessInstance()) {
            System.err.println("Headless environment, skipping test");
            return;
        }
        if (!VKEnv.isVulkanEnabled()) {
            throw new RuntimeException("Vulkan is not enabled");
        }

        GraphicsConfiguration vkConfig = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();

        VolatileImage image =
                vkConfig.createCompatibleVolatileImage(W, H, Transparency.TRANSLUCENT);

        int state = image.validate(vkConfig);

        System.out.println("validate(VK config) = " + getValidateStr(state));

        BufferedImage backing = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = backing.createGraphics();
        GraphicsConfiguration bufImgConfig = g.getDeviceConfiguration();
        g.dispose();

        state = image.validate(bufImgConfig);

        if (state != VolatileImage.IMAGE_INCOMPATIBLE) {
            throw new RuntimeException("Expected IMAGE_INCOMPATIBLE for a non-Vulkan config, got " +
                    getValidateStr(state));
        }
    }

    private static String getValidateStr(int viState) {
        String viStateStr = switch (viState) {
            case VolatileImage.IMAGE_OK -> "IMAGE_OK";
            case VolatileImage.IMAGE_INCOMPATIBLE -> "IMAGE_INCOMPATIBLE";
            case VolatileImage.IMAGE_RESTORED -> "IMAGE_RESTORED";
            default -> "UNKNOWN";
        };
        return viStateStr;
    }
}
