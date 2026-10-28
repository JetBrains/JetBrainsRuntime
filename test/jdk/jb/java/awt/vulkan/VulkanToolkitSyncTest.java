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
import sun.java2d.vulkan.VKRenderQueue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.image.VolatileImage;

/*
 * @test
 * @requires os.family == "windows"
 * @summary Toolkit.sync() flushes the Vulkan render queue on Windows, as it
 *          does the OpenGL, Direct3D and Skia queues (and the Vulkan queue on
 *          Wayland): drawing into a Vulkan surface leaves commands in the
 *          queue, and after Toolkit.sync() none are left. Without it, work
 *          stayed queued after a sync, and RenderPerfTest's volatile mode
 *          timed only the queueing. Without Vulkan there is nothing to test.
 * @modules java.desktop/sun.java2d.vulkan java.desktop/sun.java2d.pipe
 * @run main/othervm -Dsun.java2d.vulkan=Force VulkanToolkitSyncTest
 */
public class VulkanToolkitSyncTest {

    public static void main(String[] args) {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!VKEnv.isPresentationEnabled()) {
            System.out.println("Vulkan presentation unavailable: nothing to test");
            return;
        }
        VolatileImage image = gc.createCompatibleVolatileImage(256, 256);
        image.validate(gc);
        Graphics2D g = image.createGraphics();
        for (int i = 0; i < 100; i++) {
            g.setColor(new Color(i * 2, 255 - i * 2, 128));
            g.fillRect(i, i, 50, 50);
        }
        g.dispose();
        VKRenderQueue queue = VKRenderQueue.getInstance();
        int queued = queuedBytes(queue);
        System.out.println("queued before the sync: " + queued + " bytes");
        if (queued == 0) {
            throw new RuntimeException("nothing queued: the test cannot tell whether Toolkit.sync() flushes");
        }
        Toolkit.getDefaultToolkit().sync();
        int left = queuedBytes(queue);
        System.out.println("queued after the sync: " + left + " bytes");
        if (left != 0) {
            throw new RuntimeException("Toolkit.sync() left " + left + " bytes in the Vulkan render queue");
        }
        System.out.println("PASSED");
    }

    static int queuedBytes(VKRenderQueue queue) {
        queue.lock();
        try {
            return queue.getBuffer().position();
        } finally {
            queue.unlock();
        }
    }
}
