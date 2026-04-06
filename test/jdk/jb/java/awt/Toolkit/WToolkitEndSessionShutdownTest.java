/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Panel;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * bug JBR-9933
 * @key headful
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.awt:+open
 * @summary Simulates Windows system shutdown by posting WM_QUERYENDSESSION
 *          and WM_ENDSESSION to the AWT toolkit window via FFM, then verifies
 *          the JVM exits within a reasonable time. This directly triggers the
 *          race condition in awt_Toolkit.cpp where the WM_ENDSESSION handler's
 *          nested MessageLoop() can prevent WToolkit.shutdown() from completing.
 * @run main/othervm WToolkitEndSessionShutdownTest
 */
public class WToolkitEndSessionShutdownTest {

    // Maximum time to wait for the child JVM to exit (seconds).
    // Normal shutdown takes < 5s. The bug causes 18+ minute hangs.
    private static final int CHILD_TIMEOUT_SEC = 45;

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && "child".equals(args[0])) {
            runChild();
            return;
        }
        runDriver();
    }

    /**
     * Driver: spawns a child JVM that will self-inject WM_QUERYENDSESSION
     * and WM_ENDSESSION messages to its own AWT toolkit window, then
     * verifies the child exits within timeout.
     */
    private static void runDriver() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(
                "--enable-native-access=ALL-UNNAMED",
                "-Dsun.java2d.d3d=false",
                WToolkitEndSessionShutdownTest.class.getSimpleName(), "child");
        Process p = pb.start();
        OutputAnalyzer output = new OutputAnalyzer(p);

        boolean exited = p.waitFor(CHILD_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!exited) {
            // Kill the hung process first so OutputAnalyzer readers can finish
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
            System.err.println("Child process did not exit within "
                    + CHILD_TIMEOUT_SEC + " seconds — WToolkit.shutdown() likely hung.");
            System.err.println("--- child stdout ---");
            System.err.println(output.getStdout());
            System.err.println("--- child stderr ---");
            System.err.println(output.getStderr());
            throw new RuntimeException(
                    "FAIL: Child JVM hung during WM_ENDSESSION-triggered AWT shutdown "
                    + "(did not exit within " + CHILD_TIMEOUT_SEC + "s). See JBR-9933.");
        }

        output.shouldContain("FRAME_VISIBLE");
        output.shouldContain("POSTING_ENDSESSION");

        System.out.println("PASS: Child JVM exited after WM_ENDSESSION simulation within timeout "
                + "(exit code: " + p.exitValue() + ").");
    }

    /**
     * Child: creates an AWT Frame with continuous repainting, then posts
     * WM_QUERYENDSESSION and WM_ENDSESSION to its own AWT window to
     * simulate Windows system shutdown.
     *
     * The sequence mirrors real OS shutdown:
     * 1. WM_QUERYENDSESSION → WndProc raises SIGTERM → JVM shutdown hooks start
     * 2. WM_ENDSESSION → WndProc enters nested MessageLoop() → race with
     *    WToolkit.shutdown()'s QuitMessageLoop()
     *
     * If the bug is present, the nested MessageLoop never exits and
     * WToolkit.shutdown() polls IsDisposed() forever.
     */
    private static void runChild() throws Throwable {
        // WM_QUERYENDSESSION = 0x0011, WM_ENDSESSION = 0x0016
        final int WM_QUERYENDSESSION = 0x0011;
        final int WM_ENDSESSION = 0x0016;
        // ENDSESSION_CLOSEAPP = 0x00000001
        final long ENDSESSION_CLOSEAPP = 0x00000001L;

        Toolkit.getDefaultToolkit();

        CountDownLatch frameVisible = new CountDownLatch(1);

        Frame frame = new Frame("JBR-9933 EndSession Test");
        Panel panel = new Panel() {
            private int n = 0;

            @Override
            public void paint(Graphics g) {
                g.setColor((n++ % 2 == 0) ? Color.RED : Color.BLUE);
                g.fillRect(0, 0, getWidth(), getHeight());
                // Continuous repaint to simulate JCEF message generation
                repaint();
            }
        };
        panel.setPreferredSize(new Dimension(400, 300));
        frame.add(panel);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                frameVisible.countDown();
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        if (!frameVisible.await(10, TimeUnit.SECONDS)) {
            System.err.println("Frame did not become visible");
            System.exit(1);
        }
        System.out.println("FRAME_VISIBLE");

        // Let painting run to fill the message queue
        Thread.sleep(2000);

        // Get native HWND
        long hwnd = getFrameHwnd(frame);
        System.out.println("HWND=" + hwnd);

        // Set up FFM to call PostMessageW
        Linker linker = Linker.nativeLinker();
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());

        // BOOL PostMessageW(HWND hWnd, UINT Msg, WPARAM wParam, LPARAM lParam)
        // On 64-bit Windows: HWND=ptr, UINT=32bit, WPARAM=ptr-sized, LPARAM=ptr-sized
        // We use ADDRESS for pointer-sized types.
        MethodHandle postMessageW = linker.downcallHandle(
                user32.find("PostMessageW").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,    // BOOL return
                        ValueLayout.ADDRESS,     // HWND hWnd
                        ValueLayout.JAVA_INT,    // UINT Msg
                        ValueLayout.ADDRESS,     // WPARAM wParam
                        ValueLayout.ADDRESS      // LPARAM lParam
                )
        );

        MemorySegment hwndSeg = MemorySegment.ofAddress(hwnd);

        // Post WM_QUERYENDSESSION — this triggers JVM_RaiseSignal(SIGTERM)
        // in the AWT toolkit's WndProc, starting the JVM shutdown sequence.
        System.out.println("POSTING_ENDSESSION");
        System.out.flush();

        int r1 = (int) postMessageW.invoke(
                hwndSeg,
                WM_QUERYENDSESSION,
                MemorySegment.ofAddress(0L),
                MemorySegment.ofAddress(ENDSESSION_CLOSEAPP));
        System.out.println("PostMessage WM_QUERYENDSESSION: " + r1);

        // Brief pause for SIGTERM handler to start the shutdown sequence
        Thread.sleep(500);

        // Post WM_ENDSESSION — this triggers the nested MessageLoop() in
        // the WM_ENDSESSION handler. If the race condition hits, the nested
        // loop prevents Dispose() from completing and the process hangs.
        int r2 = (int) postMessageW.invoke(
                hwndSeg,
                WM_ENDSESSION,
                MemorySegment.ofAddress(1L),               // wParam = TRUE (session IS ending)
                MemorySegment.ofAddress(ENDSESSION_CLOSEAPP));
        System.out.println("PostMessage WM_ENDSESSION: " + r2);

        // The JVM should now be shutting down via SIGTERM → shutdown hooks →
        // WToolkit.shutdown(). Keep the main thread alive to not interfere.
        // The driver will kill this process if it doesn't exit in time.
        Thread.sleep(300_000);
    }

    /**
     * Extracts the native HWND from an AWT Frame via its peer.
     */
    private static long getFrameHwnd(Frame frame) {
        try {
            Object peer = sun.awt.AWTAccessor.getComponentAccessor().getPeer(frame);
            if (peer == null) {
                throw new RuntimeException("Frame has no peer");
            }
            java.lang.reflect.Method getHWnd = peer.getClass().getMethod("getHWnd");
            getHWnd.setAccessible(true);
            return (long) getHWnd.invoke(peer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get HWND from Frame peer", e);
        }
    }
}
