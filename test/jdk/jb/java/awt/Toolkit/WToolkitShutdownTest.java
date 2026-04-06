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
 * @summary Verifies that WToolkit.shutdown() completes and the JVM exits
 *          within a reasonable time when System.exit() is called with an
 *          active AWT window generating continuous repaint messages.
 *          Regression test for hang in WToolkit.shutdown() native polling
 *          loop where IsDisposed() never returns true.
 * @run main/othervm WToolkitShutdownTest
 */
public class WToolkitShutdownTest {

    // Maximum time to wait for the child JVM to exit (seconds).
    // Normal shutdown takes < 5s. The bug causes 18+ minute hangs.
    private static final int CHILD_TIMEOUT_SEC = 45;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            runChild();
            return;
        }
        runDriver();
    }

    /**
     * Driver: spawns a child JVM, waits for it to exit within timeout.
     */
    private static void runDriver() throws Exception {
        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(
                "-Dsun.java2d.d3d=false",
                WToolkitShutdownTest.class.getSimpleName(), "child");
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
                    "FAIL: Child JVM hung during AWT shutdown (did not exit within "
                    + CHILD_TIMEOUT_SEC + "s). See JBR-9933.");
        }

        output.shouldHaveExitValue(0);
        output.shouldContain("FRAME_VISIBLE");
        output.shouldContain("CALLING_EXIT");
        System.out.println("PASS: Child JVM exited cleanly within timeout.");
    }

    /**
     * Child: creates an AWT Frame with continuous repainting (simulating
     * the JCEF message generation that aggravates the shutdown hang),
     * then calls System.exit(0) to trigger WToolkit shutdown hooks.
     */
    private static void runChild() throws Exception {
        // Force toolkit initialization
        Toolkit.getDefaultToolkit();

        CountDownLatch frameVisible = new CountDownLatch(1);

        Frame frame = new Frame("JBR-9933 Shutdown Test");
        Panel panel = new Panel() {
            private int n = 0;

            @Override
            public void paint(Graphics g) {
                // Continuous repaint to generate native messages, simulating
                // the JCEF browser window behavior described in JBR-9933
                g.setColor((n++ % 2 == 0) ? Color.RED : Color.BLUE);
                g.fillRect(0, 0, getWidth(), getHeight());
                // Request another repaint to keep the message pump busy
                repaint();
            }
        };
        panel.setPreferredSize(new Dimension(400, 300));
        frame.add(panel);
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                frameVisible.countDown();
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Wait for the frame to be fully visible and painting
        if (!frameVisible.await(10, TimeUnit.SECONDS)) {
            System.err.println("Frame did not become visible within 10s");
            System.exit(1);
        }
        System.out.println("FRAME_VISIBLE");

        // Let painting run for a bit to fill the message queue
        Thread.sleep(2000);

        // Trigger JVM shutdown — this will invoke WToolkit's shutdown hook
        // which calls native WToolkit.shutdown() → QuitMessageLoop() →
        // while (!IsDisposed()) Sleep(100).
        // If the bug is present and the race hits, this hangs forever.
        System.out.println("CALLING_EXIT");
        System.exit(0);
    }
}
