/*
 * Copyright 2026 JetBrains s.r.o.
 * Copyright (c) 2026, 2016, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @key headful
 * @summary Tests that nothing prevents windows from being removed from the global list
 *          after they have been disposed
 * @library /javax/swing/regtesthelpers
 * @modules java.desktop/sun.awt
 *          java.desktop/sun.java2d
 * @build Util
 * @run main/othervm -Xms32M -Xmx32M WLWindowsLeak
 */

import java.awt.Color;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.Window;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import com.sun.management.HotSpotDiagnosticMXBean;
import sun.awt.AppContext;
import sun.java2d.Disposer;

public class WLWindowsLeak {

    public static final int WINDOWS_COUNT = 100;
    private static volatile boolean disposerPhantomComplete;

    public static void main(String[] args) throws Exception {
        spawnWindows();

        Disposer.addRecord(new Object(), () -> disposerPhantomComplete = true);

        while (!disposerPhantomComplete) {
            Util.generateOOME();
        }

        Vector<WeakReference<Window>> windowList =
                (Vector<WeakReference<Window>>) AppContext.getAppContext().get(Window.class);

        if (windowList != null && !windowList.isEmpty()) {
            dumpHeap("heap_dump_live_windows.hprof");
            System.out.println("Live window list:");
            windowList.forEach(ref -> System.out.println(ref.get()));
            throw new RuntimeException("Test FAILED: Window list is not empty: " + windowList.size());
        }

        System.out.println("Test PASSED");
    }

    private static void dumpHeap(String filePath) {
        try {
            HotSpotDiagnosticMXBean mxBean = ManagementFactory
                    .getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            mxBean.dumpHeap(filePath, true); // true = live objects only
            System.out.println("Heap dump created at: " + filePath);
        } catch (IOException e) {
            System.err.println("Failed to dump heap: " + e.getMessage());
        }
    }

    private static void spawnWindows() throws Exception {
        List<WeakReference<Frame>> frameList = new ArrayList<>();
        Robot r = new Robot();

        for (int i = 0; i < WINDOWS_COUNT; i++) {
            Frame f = new Frame(String.format("Frame %d", i));
            f.setBackground(Color.WHITE);
            f.setSize(100, 100);
            f.setVisible(true);
            frameList.add(new WeakReference<>(f));
            Thread.sleep(42);
        }
        r.waitForIdle();

        frameList.forEach(ref -> {
            ref.get().setVisible(false);
        });
        r.waitForIdle();

        frameList.forEach(ref -> {
            var f = ref.get();
            if (f != null) f.dispose();
        });
        frameList.clear();
        r.waitForIdle(); // to make sure no events hold a reference to frames
        System.gc();
    }
}
