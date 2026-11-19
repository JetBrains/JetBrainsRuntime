/*
 * Copyright 2024-2026 JetBrains s.r.o.
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

import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.VolatileImage;

/*
 * @test
 * @requires os.family == "linux" | os.family == "windows"
 * @summary Verifies composites in opaque and translucent modes.
 * @modules java.desktop/sun.java2d.vulkan
 * @library /java/awt/AlphaComposite/lib
 * @build AlphaCompositeTestRunner
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=true VulkanCompositeTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=false VulkanCompositeTest
 */

public class VulkanCompositeTest {
    static boolean shouldSkipTest(AlphaCompositeTestRunner.TestCaseDescription testCase) {
        // AA parallelograms are currently drawn as non-AA in Vulkan.
        // This must be fixed for the test case to work.
        return testCase.aaTestVariant() == AlphaCompositeTestRunner.AATestVariant.AA_RECTANGLE;
    }

    record TransparencyWithDescription(int transparency, String description) {}

    static final TransparencyWithDescription[] TRANSPARENCIES = {
            new TransparencyWithDescription(Transparency.TRANSLUCENT, "TRANSLUCENT"),
            new TransparencyWithDescription(Transparency.OPAQUE, "OPAQUE")
    };

    static final AlphaCompositeTestRunner.RunOptions RUN_OPTIONS = new AlphaCompositeTestRunner.RunOptions(
            AlphaCompositeTestRunner.TranslucentOutputOnOpaqueDstPolicy.MUST_UNPREMULTIPLY,
            AlphaCompositeTestRunner.FullyTransparentOnOpaqueDstPolicy.DONT_CARE,
            2.0f / 255.0f,
            VulkanCompositeTest::shouldSkipTest
    );

    static void main(String[] args) {
        if (!VKEnv.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }
        boolean[] success = {true};
        for (var transparencyWithDescription : TRANSPARENCIES) {
            VKEnv.getDevices().flatMap(VKGPU::getOffscreenGraphicsConfigs).forEach(vkgc -> {
                GraphicsConfiguration gc = (GraphicsConfiguration) vkgc;
                VolatileImage image = gc.createCompatibleVolatileImage(AlphaCompositeTestRunner.W, AlphaCompositeTestRunner.H, transparencyWithDescription.transparency);
                if (!AlphaCompositeTestRunner.run(image, gc, RUN_OPTIONS, vkgc.descriptorString() + ", " + transparencyWithDescription.description)) {
                    success[0] = false;
                }
            });
        }
        if (!success[0]) {
            throw new Error("Some cases failed, see stderr for details.");
        }
    }
}
