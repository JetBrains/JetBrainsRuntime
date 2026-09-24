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

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.awt.image.SurfaceManager;
import sun.java2d.Disposer;
import sun.java2d.DisposerRecord;
import sun.java2d.SurfaceData;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGraphicsConfig;
import sun.java2d.vulkan.VKSurfaceData;

import java.awt.Graphics2D;
import java.awt.image.VolatileImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

abstract class VulkanDisposeTest {

    static final int SKIPPED_EXIT_CODE = 2;

    private static final int FILL_BYTE = 0xec;

    private static final int MAX_GC_ITERATIONS = 1000;

    protected VolatileImage a;

    protected VolatileImage b;

    protected VulkanDisposeTest() {
        try {
            if (!VKEnv.isVulkanEnabled()) {
                throw new Error("Vulkan not enabled");
            }
            VKGraphicsConfig gc =
                    VKEnv.getDevices().findFirst().get().getOffscreenGraphicsConfigs().findFirst().get();
            a = gc.createCompatibleVolatileImage(100, 100, VolatileImage.TRANSLUCENT, VKSurfaceData.RT_TEXTURE);
            b = gc.createCompatibleVolatileImage(100, 100, VolatileImage.TRANSLUCENT, VKSurfaceData.RT_TEXTURE);

            // Blit a onto b
            Graphics2D g = (Graphics2D) b.getGraphics();
            g.drawImage(a, 0, 0, null);
            g.dispose();

            test();
        } catch (SkippedException e) {
            // runInChildJVM turns this exit code back into a skip.
            System.out.println("Skipped: " + e.getMessage());
            System.exit(SKIPPED_EXIT_CODE);
        }
    }
    protected abstract void test();

    protected final void disposeSource() {
        WeakReference<SurfaceData> ref = surfaceDataRef(a);
        a = null;
        awaitDisposal(ref);
    }

    protected final void disposeDestination() {
        WeakReference<SurfaceData> ref = surfaceDataRef(b);
        b = null;
        awaitDisposal(ref);
    }

    private static WeakReference<SurfaceData> surfaceDataRef(VolatileImage image) {
        return new WeakReference<>(SurfaceManager.getManager(image).getPrimarySurfaceData());
    }

    private static void awaitDisposal(WeakReference<SurfaceData> ref)  {
        for (int i = 0; i < MAX_GC_ITERATIONS && ref.get() != null; i++) {
            System.gc();
        }
        if (ref.get() != null) {
            throw new SkippedException(
                    "SurfaceData was not collected after " + MAX_GC_ITERATIONS + " iterations");
        }

        // Make sure that we processed DISPOSE_SURFACE and freed the native VKSDOps
        CountDownLatch disposed = new CountDownLatch(1);
        Object marker = new Object();
        Disposer.addRecord(marker, (DisposerRecord) disposed::countDown);
        marker = null;
        try {
            for (int i = 0; i < MAX_GC_ITERATIONS && !disposed.await(1, TimeUnit.MILLISECONDS); i++) {
                System.gc();
            }
        }  catch (InterruptedException e) {
            throw new SkippedException("Java2D Disposer wait was interrupted");
        }
        if (disposed.getCount() != 0) {
            throw new SkippedException(
                    "Java2D Disposer did not run after " + MAX_GC_ITERATIONS + " iterations");
        }
    }

    static void runInChildJVM(String className) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "--add-exports", "java.desktop/sun.java2d=ALL-UNNAMED",
                "--add-exports", "java.desktop/sun.java2d.vulkan=ALL-UNNAMED",
                "--add-exports", "java.desktop/sun.awt.image=ALL-UNNAMED",
                "-Djava.awt.headless=true",
                "-Dsun.java2d.vulkan=True",
                "-Dsun.java2d.vulkan.leOptimizations=true"));
        command.add(className);
        command.add("child");

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(command);
        // Disabling the tcache, it pushes the block through the regular free path, where the
        // poisoning actually happens and where the block is not immediately recycled.
        pb.environment().put("GLIBC_TUNABLES",
                "glibc.malloc.perturb=" + FILL_BYTE + ":glibc.malloc.tcache_count=0");
        // Legacy equivalent, for glibc versions without tunables.
        pb.environment().put("MALLOC_PERTURB_", String.valueOf(FILL_BYTE));

        OutputAnalyzer output = ProcessTools.executeProcess(pb);
        output.reportDiagnosticSummary();
        if (output.getExitValue() == SKIPPED_EXIT_CODE) {
            throw new SkippedException("Child JVM could not run the test, see its output above");
        }
        output.shouldHaveExitValue(0);
    }
}
