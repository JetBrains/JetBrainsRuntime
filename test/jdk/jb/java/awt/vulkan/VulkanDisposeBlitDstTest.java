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

import sun.java2d.vulkan.VKRenderQueue;

import java.awt.Graphics2D;

/*
 * @test
 * @requires os.family == "linux"
 * @library /test/lib
 * @summary Verifies that disposal of blit destination image doesn't crash the process.
 * @modules java.desktop/sun.java2d.vulkan:+open java.desktop/sun.java2d:+open java.desktop/sun.awt.image:+open
 * @run main/othervm/timeout=300 VulkanDisposeBlitDstTest
 */


public class VulkanDisposeBlitDstTest extends VulkanDisposeTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runInChildJVM(VulkanDisposeBlitDstTest.class.getName());
        } else {
            new VulkanDisposeBlitDstTest();
        }
    }

    @Override
    protected void test() {
        // Dispose b
        disposeDestination();

        // Mutate a
        Graphics2D g = (Graphics2D) a.getGraphics();
        g.fillRect(10, 10, 10, 10);
        g.dispose();
        VKRenderQueue.getInstance().flushNow();
    }
}
