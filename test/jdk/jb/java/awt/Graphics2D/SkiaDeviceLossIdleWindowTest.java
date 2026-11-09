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

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: after a device loss every Skia window returns to Skia,
 *          also one that did not repaint before the recovery.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDeviceLossIdleWindowTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaDeviceLossIdleWindowTest
 */

import java.awt.Color;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import sun.java2d.SurfaceData;
import sun.java2d.skia.Win32SKGraphicsConfig;

/**
 * The idle window's surface is replaced by the recovery (a display change) while it still holds
 * the swap chain of the lost device. Taking that swap chain over to the new device failed on Dawn
 * ("D3D SwapChain cannot switch between D3D Devices"), and the idle window stayed on GDI. Two
 * frames, one repainting all the time, one idle; the loss is simulated. A window has recovered
 * when it has a new configuration and a new Skia surface, and both keep them for two seconds.
 * The test runs in a child JVM with the release traces of errors on (PipelineComparison.ranInChild):
 * no surface may be wrapped while it is another context's surface (SKSurfaceState::wrap's assertion),
 * and no texture paint image of a context the recovery disposes may outlive it (SKGraphicsConfig's
 * destructor, which tracks the images only while those traces are on); nothing in Java can provoke
 * either.
 */
public class SkiaDeviceLossIdleWindowTest {
    /** How long both windows get to return to Skia after the simulated loss. */
    static final long RECOVERY_TIMEOUT_MS = 15_000;
    /** How long both must keep their new configuration and surface to count as recovered. */
    static final long STABLE_MS = 2000;
    /** The busy window repaints at about 60 Hz. */
    private static final int REPAINT_INTERVAL_MS = 16;
    /** After both windows are on Skia: both have painted and presented, the idle one holds its swap chain. */
    private static final long PRESENTED_MS = 1000;
    /** How often the windows are polled for their recovery. */
    private static final long POLL_MS = 50;
    private static final int FRAME_WIDTH = 300;
    private static final int FRAME_HEIGHT = 200;
    private static final int FRAME_Y = 100;
    private static final int BUSY_FRAME_X = 100;
    private static final int IDLE_FRAME_X = 450;

    public static void main(String[] args) throws Exception {
        if (PipelineComparison.ranInChild(SkiaDeviceLossIdleWindowTest.class, args, PipelineComparison.WRAPPED_BY_ANOTHER_CONTEXT_TRACE,
                                          PipelineComparison.IMAGES_OUTLIVE_CONTEXT_TRACE)) {
            return;   // the child ran the test, with the assertions' traces on
        }
        PipelineComparison.requireRequestedAPI();
        AtomicReference<JFrame> busyReference = new AtomicReference<>();
        AtomicReference<JFrame> idleReference = new AtomicReference<>();
        AtomicReference<Timer> timerReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame busy = frame("busy", Color.GREEN, BUSY_FRAME_X);
            JFrame idle = frame("idle", Color.BLUE, IDLE_FRAME_X);
            Timer timer = new Timer(REPAINT_INTERVAL_MS, event -> busy.getContentPane().repaint());
            timer.start();
            timerReference.set(timer);
            busyReference.set(busy);
            idleReference.set(idle);
        });
        JFrame busy = busyReference.get();
        JFrame idle = idleReference.get();
        try {
            String busyBefore = WindowSurfaces.await(busy, surface -> surface.contains(PipelineComparison.SKIA_PACKAGE),
                                                     WindowSurfaces.TIMEOUT_MS);
            String idleBefore = WindowSurfaces.await(idle, surface -> surface.contains(PipelineComparison.SKIA_PACKAGE),
                                                     WindowSurfaces.TIMEOUT_MS);
            System.out.println("before the loss: busy " + busyBefore + ", idle " + idleBefore);
            if (!busyBefore.contains(PipelineComparison.SKIA_PACKAGE) || !idleBefore.contains(PipelineComparison.SKIA_PACKAGE)) {
                throw new RuntimeException("the windows do not render on Skia: " + busyBefore + ", " + idleBefore);
            }
            Thread.sleep(PRESENTED_MS);
            Before busyState = new Before(busy);
            Before idleState = new Before(idle);
            Win32SKGraphicsConfig.simulateDeviceLoss(false);
            long lossAt = System.nanoTime();
            long deadline = lossAt + RECOVERY_TIMEOUT_MS * 1_000_000;
            long recoveredAt = 0;
            while (System.nanoTime() < deadline) {
                if (busyState.recovered(busy) && idleState.recovered(idle)) {
                    if (recoveredAt == 0) {
                        recoveredAt = System.nanoTime();
                    } else if (System.nanoTime() - recoveredAt >= STABLE_MS * 1_000_000) {
                        break;
                    }
                } else {
                    recoveredAt = 0;
                }
                Thread.sleep(POLL_MS);
            }
            String busyAfter = WindowSurfaces.of(busy);
            String idleAfter = WindowSurfaces.of(idle);
            System.out.printf("after the loss: busy %s, idle %s%s%n", busyAfter, idleAfter,
                    recoveredAt != 0 ? String.format(", recovered after %.1f s", (recoveredAt - lossAt) / 1e9) : "");
            if (!busyState.recovered(busy)) {
                throw new RuntimeException("the repainting window did not return to Skia within " + RECOVERY_TIMEOUT_MS
                        + " ms: " + busyAfter);
            }
            if (!idleState.recovered(idle)) {
                throw new RuntimeException("the idle window did not return to Skia within " + RECOVERY_TIMEOUT_MS
                        + " ms: " + idleAfter);
            }
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                timerReference.get().stop();   // else the event thread keeps the VM alive
                busy.dispose();
                idle.dispose();
            });
        }
    }

    /** A window's configuration and surface before the loss. */
    record Before(GraphicsConfiguration config, SurfaceData surface) {
        Before(JFrame frame) {
            this(frame.getGraphicsConfiguration(), WindowSurfaces.surfaceData(frame));
        }

        /** Whether the window is on a new configuration with a new Skia surface. */
        boolean recovered(JFrame frame) {
            SurfaceData current = WindowSurfaces.surfaceData(frame);
            return frame.getGraphicsConfiguration() != config && current != null && current != surface
                   && current.getClass().getName().contains(PipelineComparison.SKIA_PACKAGE);
        }
    }

    static JFrame frame(String title, Color color, int x) {
        JFrame frame = new JFrame("SkiaDeviceLossIdleWindowTest " + title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                graphics.setColor(color);
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        });
        frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
        frame.setLocation(x, FRAME_Y);
        frame.setVisible(true);
        return frame;
    }
}
