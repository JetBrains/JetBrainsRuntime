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
 * @summary Regression test for JBR-10654: a Skia window, and a VolatileImage created before, survive
 *          the loss of the GPU device, and a device that keeps failing leaves the window on GDI.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest transient
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest persistent
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest transient
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest persistent
 */

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import sun.java2d.skia.Win32SKGraphicsConfig;

/**
 * After a single loss (a driver reset) the window renders on Skia again on a new device; when
 * every new device is lost as well (a driver that keeps failing), the window settles on GDI after
 * a few recoveries instead of recreating its configuration again and again, and keeps painting.
 * A VolatileImage created on the window's configuration before the loss validates against the
 * window's configuration afterwards without an exception (recreated where it is incompatible, as
 * an application does), and renders: on Skia after a single loss, wherever the configuration
 * renders after repeated ones. The loss is simulated (a real one, a TDR, needs an elevated shell).
 * The test runs in a child JVM with the release traces of errors on (PipelineComparison.ranInChild):
 * no surface may be wrapped while it is another context's surface (SKSurfaceState::wrap's assertion),
 * and no texture paint image of a context the recovery disposes may outlive it (SKGraphicsConfig's
 * destructor, which tracks the images only while those traces are on); nothing in Java can provoke
 * either.
 */
public class SkiaDeviceLossRecoveryTest {
    /** How long the window is watched after the loss. */
    static final int SECONDS_AFTER_LOSS = 10;
    // one per recovery (each recreates the configurations: displayChanged), and Win32SKScreenUpdateManager
    // recovers at most MAX_RECOVERY_ATTEMPTS = 3 losses in a row
    static final int MAX_CONFIGURATIONS = 3;
    /** The recovery has settled when the configuration stayed this long at the end of the watch. */
    private static final double MIN_QUIET_SECONDS = 3;
    static final int IMAGE_WIDTH = 64;
    static final int IMAGE_HEIGHT = 48;
    /** The window repaints at about 60 Hz. */
    private static final int REPAINT_INTERVAL_MS = 16;
    /** After the window is shown: it has painted and presented on Skia. */
    private static final long SHOWN_MS = 1500;
    /** How often the window's configuration and surface are polled after the loss. */
    private static final long POLL_MS = 100;
    /** The window must still paint at the end: how long its paints are counted. */
    private static final long PAINT_WINDOW_MS = 500;
    /** The blue the image is filled with after the loss, as an RGB value. */
    private static final int BLUE = 0x0000ff;
    private static final int FRAME_WIDTH = 300;
    private static final int FRAME_HEIGHT = 200;
    private static final int FRAME_X = 100;
    private static final int FRAME_Y = 100;

    public static void main(String[] args) throws Exception {
        if (PipelineComparison.ranInChild(SkiaDeviceLossRecoveryTest.class, args, PipelineComparison.WRAPPED_BY_ANOTHER_CONTEXT_TRACE,
                                          PipelineComparison.IMAGES_OUTLIVE_CONTEXT_TRACE)) {
            return;   // the child ran the test, with the assertions' traces on
        }
        PipelineComparison.requireRequestedAPI();
        boolean persistent = args.length > 0 && args[0].equals("persistent");
        AtomicInteger paints = new AtomicInteger();
        AtomicReference<JFrame> frameReference = new AtomicReference<>();
        AtomicReference<Timer> timerReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("SkiaDeviceLossRecoveryTest");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    int paint = paints.incrementAndGet();
                    // two greens in turn, so that every paint changes the window
                    graphics.setColor((paint & 1) == 0 ? Color.GREEN : new Color(0, 200, 0));
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            frame.setContentPane(panel);
            frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
            frame.setLocation(FRAME_X, FRAME_Y);
            frame.setVisible(true);
            Timer timer = new Timer(REPAINT_INTERVAL_MS, event -> panel.repaint());
            timer.start();
            timerReference.set(timer);
            frameReference.set(frame);
        });
        JFrame frame = frameReference.get();
        try {
            Thread.sleep(SHOWN_MS);
            String before = WindowSurfaces.of(frame);
            System.out.println("before the loss: " + before);
            if (!before.contains(PipelineComparison.SKIA_PACKAGE)) {
                throw new RuntimeException("the window does not render on Skia: " + before);
            }
            GraphicsConfiguration configBefore = frame.getGraphicsConfiguration();
            VolatileImage image = configBefore.createCompatibleVolatileImage(IMAGE_WIDTH, IMAGE_HEIGHT);
            image.validate(configBefore);
            fill(image, Color.RED);
            image.getSnapshot();
            int lastConfiguration = System.identityHashCode(configBefore);
            System.out.printf("configuration before the loss: %08x%n", lastConfiguration);
            Win32SKGraphicsConfig.simulateDeviceLoss(persistent);
            long lossAt = System.nanoTime();
            Set<Integer> configurations = new LinkedHashSet<>();   // the configurations the window gets after the loss
            List<String> changes = new ArrayList<>();
            long lastChangeAt = lossAt;
            String surface = before;
            while (System.nanoTime() - lossAt < SECONDS_AFTER_LOSS * 1_000_000_000L) {
                Thread.sleep(POLL_MS);
                int configuration = System.identityHashCode(frame.getGraphicsConfiguration());
                surface = WindowSurfaces.of(frame);
                if (configuration != lastConfiguration) {
                    lastConfiguration = configuration;
                    lastChangeAt = System.nanoTime();
                    configurations.add(configuration);
                    changes.add(String.format("%.1f s: %08x %s", (lastChangeAt - lossAt) / 1e9, configuration, surface));
                }
            }
            int paintsBefore = paints.get();
            Thread.sleep(PAINT_WINDOW_MS);
            int paintsInWindow = paints.get() - paintsBefore;
            changes.forEach(change -> System.out.println("  " + change));
            double quietFor = (System.nanoTime() - lastChangeAt) / 1e9;
            System.out.printf("%d configurations after the loss, the last change %.1f s ago, surface %s, %d paints in %d ms%n",
                    configurations.size(), quietFor, surface, paintsInWindow, PAINT_WINDOW_MS);
            if (paintsInWindow == 0) {
                throw new RuntimeException("the window stopped painting");
            }
            if (configurations.isEmpty()) {
                throw new RuntimeException("the window kept its configuration: the loss was not recovered from");
            }
            if (configurations.size() > MAX_CONFIGURATIONS) {
                throw new RuntimeException(configurations.size() + " configurations after the loss: recovery does not settle");
            }
            if (quietFor < MIN_QUIET_SECONDS) {
                throw new RuntimeException("the configuration still changes " + SECONDS_AFTER_LOSS + " s after the loss");
            }
            boolean onExpectedPipeline = persistent ? surface.contains("GDIWindowSurfaceData")
                                                    : surface.contains(PipelineComparison.SKIA_PACKAGE);
            if (!onExpectedPipeline) {
                throw new RuntimeException("expected the window on " + (persistent ? "GDI" : "Skia") + ", it is on " + surface);
            }
            checkImageAfterLoss(image, frame.getGraphicsConfiguration(), persistent);
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                timerReference.get().stop();   // else the event thread keeps the VM alive
                frame.dispose();
            });
        }
    }

    /** The image from before the loss, validated against the window's configuration now, as an application does. */
    private static void checkImageAfterLoss(VolatileImage image, GraphicsConfiguration currentConfig, boolean persistent)
            throws Exception {
        int status = image.validate(currentConfig);
        VolatileImage target = status == VolatileImage.IMAGE_INCOMPATIBLE
                ? currentConfig.createCompatibleVolatileImage(IMAGE_WIDTH, IMAGE_HEIGHT) : image;
        BufferedImage snapshot = PipelineComparison.keepingContents(currentConfig, () -> {
            fill(target, Color.BLUE);
            return target.getSnapshot();
        }, target);
        int center = snapshot.getRGB(IMAGE_WIDTH / 2, IMAGE_HEIGHT / 2) & 0xffffff;
        String surface = PipelineComparison.surfaceClassName(target);
        System.out.printf("the image from before the loss: validate %d%s, surface %s, center %06x%n", status,
                target != image ? " (recreated)" : "", surface, center);
        if (center != BLUE) {
            throw new RuntimeException("the image from before the loss renders " + Integer.toHexString(center) + " on " + surface);
        }
        if (!persistent && !surface.contains(PipelineComparison.SKIA_PACKAGE)) {
            throw new RuntimeException("the image from before the loss is not on Skia after the recovery: " + surface);
        }
    }

    private static void fill(VolatileImage image, Color color) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        graphics.dispose();
    }
}
