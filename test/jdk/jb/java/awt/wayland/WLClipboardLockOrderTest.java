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

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/*
 * @test
 * @summary Verifies that WLClipboard does not deadlock when a clipboard offer arrives
 *          while another thread is reading the clipboard (JBR-10347, regression of JBR-7896).
 *          WLClipboard.handleClipboardOffer() must not call SunClipboard.lostOwnershipNow()
 *          (which synchronizes on the clipboard's monitor) while holding dataLock, because
 *          SunClipboard.getContents()/isDataFlavorAvailable() take the monitor first and then
 *          call back into WLClipboard.getClipboardFormats(), which acquires dataLock.
 * @requires os.family == "linux"
 * @key headful
 * @run main/othervm/timeout=60 -Dawt.toolkit.name=WLToolkit --add-opens java.desktop/sun.awt.wl=ALL-UNNAMED WLClipboardLockOrderTest
 */
public class WLClipboardLockOrderTest {
    private static final long JOIN_TIMEOUT_MS = 15_000;

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Clipboard clipboard = toolkit.getSystemClipboard();
        Class<?> wlClipboardClass = Class.forName("sun.awt.wl.WLClipboard");
        if (!wlClipboardClass.isInstance(clipboard)) {
            System.out.println("System clipboard is not a WLClipboard. Exiting...");
            return;
        }

        Method handleClipboardOffer = wlClipboardClass.getDeclaredMethod(
                "handleClipboardOffer", Class.forName("sun.awt.wl.WLDataOffer"));
        handleClipboardOffer.setAccessible(true);
        Method getClipboardFormats = wlClipboardClass.getDeclaredMethod("getClipboardFormats");
        getClipboardFormats.setAccessible(true);

        CountDownLatch monitorHeld = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Simulates the Wayland event dispatch of wl_data_device.selection: takes dataLock
        // and (since we don't own the clipboard) reports lost ownership. With the inverted
        // lock order, lostOwnershipNow() tries to enter the clipboard monitor while still
        // holding dataLock.
        Thread offerHandler = new Thread(() -> {
            try {
                monitorHeld.await();
                handleClipboardOffer.invoke(clipboard, (Object) null);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "offer-handler");

        // Simulates SunClipboard.getContents()/isDataFlavorAvailable(): both are synchronized
        // on the clipboard and call back into getClipboardFormats(), which takes dataLock.
        Thread contentsReader = new Thread(() -> {
            try {
                synchronized (clipboard) {
                    monitorHeld.countDown();
                    waitUntilBlocked(offerHandler);
                    getClipboardFormats.invoke(clipboard);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "contents-reader");

        // Daemon threads: if they deadlock, they must not keep the JVM alive after the failure
        // is reported.
        offerHandler.setDaemon(true);
        contentsReader.setDaemon(true);
        offerHandler.start();
        contentsReader.start();

        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        while (offerHandler.isAlive() || contentsReader.isAlive()) {
            long[] deadlocked = threadMXBean.findDeadlockedThreads();
            if (deadlocked != null) {
                StringBuilder sb = new StringBuilder("Deadlock detected:\n");
                for (ThreadInfo info : threadMXBean.getThreadInfo(deadlocked, true, true)) {
                    sb.append(info);
                }
                throw new RuntimeException(sb.toString());
            }
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Test threads did not finish in "
                        + JOIN_TIMEOUT_MS + "ms; offer-handler: " + offerHandler.getState()
                        + ", contents-reader: " + contentsReader.getState());
            }
            Thread.sleep(100);
        }

        if (failure.get() != null) {
            throw new RuntimeException("Test thread failed", failure.get());
        }

        System.out.println("Test passed: no deadlock between clipboard offer handling and clipboard reading");
    }

    // Waits until the thread is blocked on a monitor, i.e. the offer handler has advanced
    // as far as it can while the clipboard monitor is held by the current thread.
    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
        while (thread.getState() != Thread.State.BLOCKED) {
            if (!thread.isAlive() || System.currentTimeMillis() > deadline) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
