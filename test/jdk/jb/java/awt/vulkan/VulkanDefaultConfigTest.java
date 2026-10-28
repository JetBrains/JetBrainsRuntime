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
 */

import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGraphicsConfig;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/*
 * @test
 * @requires os.family == "windows"
 * @summary With Vulkan presentation enabled on Windows, the default screen
 *          device's default configuration is a Vulkan one whether or not
 *          getConfigurations() was called first, and getConfigurations()
 *          contains it: listing the configurations used to enumerate the GDI
 *          pixel formats and replace the default with a GDI configuration, so
 *          a program that lists them first (RenderPerfTest does) rendered
 *          through GDI and the software loops. Without Vulkan there is
 *          nothing to test.
 * @modules java.desktop/sun.java2d.vulkan
 * @run main/othervm -Dsun.java2d.vulkan=Force VulkanDefaultConfigTest defaultFirst
 * @run main/othervm -Dsun.java2d.vulkan=Force VulkanDefaultConfigTest listFirst
 */
public class VulkanDefaultConfigTest {

    public static void main(String[] args) {
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (!VKEnv.isPresentationEnabled()) {
            System.out.println("Vulkan presentation unavailable: nothing to test");
            return;
        }
        boolean listFirst = args.length > 0 && args[0].equals("listFirst");
        GraphicsConfiguration[] all;
        GraphicsConfiguration defaultConfig;
        if (listFirst) {
            all = gd.getConfigurations();
            defaultConfig = gd.getDefaultConfiguration();
        } else {
            defaultConfig = gd.getDefaultConfiguration();
            all = gd.getConfigurations();
        }
        GraphicsConfiguration defaultAgain = gd.getDefaultConfiguration();
        System.out.println("default " + defaultConfig + ", again " + defaultAgain + ", all " + Arrays.toString(all));
        if (!(defaultConfig instanceof VKGraphicsConfig) || !(defaultAgain instanceof VKGraphicsConfig)) {
            throw new RuntimeException("the default configuration is not a Vulkan one: " + defaultConfig + ", then " + defaultAgain);
        }
        if (!Arrays.asList(all).contains(defaultAgain)) {
            throw new RuntimeException("getConfigurations() does not contain the default configuration " + defaultAgain);
        }
        System.out.println("PASSED");
    }
}
