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

import jtreg.SkippedException;
import sun.awt.image.SurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGraphicsConfig;
import sun.java2d.vulkan.VKRenderQueue;
import sun.java2d.vulkan.VKSurfaceData;

import java.awt.*;
import java.awt.image.VolatileImage;
import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;

/*
 * @test
 * @requires os.family == "linux"
 * @library /test/lib
 * @summary Verifies that disposal of blit source image doesn't crash the process.
 * @modules java.desktop/sun.java2d.vulkan:+open java.desktop/sun.java2d:+open java.desktop/sun.awt.image:+open
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.vulkan=True -Dsun.java2d.vulkan.leOptimizations=true VulkanDisposeBlitSrcTest
 */


public class VulkanDisposeBlitSrcTest {
    public static void main(String[] args) throws Exception {
        if (!VKEnv.isVulkanEnabled()) {
            throw new Error("Vulkan not enabled");
        }

        VKGraphicsConfig gc = VKEnv.getDevices().findFirst().get().getOffscreenGraphicsConfigs().findFirst().get();
        VolatileImage a = gc.createCompatibleVolatileImage(100, 100, VolatileImage.TRANSLUCENT, VKSurfaceData.RT_TEXTURE);
        VolatileImage b = gc.createCompatibleVolatileImage(100, 100, VolatileImage.TRANSLUCENT, VKSurfaceData.RT_TEXTURE);

        // Blit a onto b
        Graphics2D g = (Graphics2D) b.getGraphics();
        g.drawImage(a, 0, 0, null);
        g.dispose();

        // Dispose a
        WeakReference<SurfaceData> ref = new WeakReference<>(SurfaceManager.getManager(a).getPrimarySurfaceData());
        MemorySegment vksd = MemorySegment.ofAddress(ref.get().getNativeOps()).reinterpret(160); // sizeof(VKSDOps)
        a = null;
        final int MAX_ITERATIONS = 1000;
        for (int i = 0; i < MAX_ITERATIONS && ref.get() != null; i++) System.gc();
        if (ref.get() != null) throw new SkippedException("SurfaceData was not collected after " + MAX_ITERATIONS + " iterations");
        // Mess a's native data
        Thread.sleep(100);
        vksd.fill((byte) 0xec);

        // Flush b
        b.getSnapshot();
    }
}
