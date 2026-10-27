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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
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

import sun.awt.AWTAccessor;
import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;
import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.Win32SKGraphicsConfig;

/**
 * @test
 * @summary A Skia window survives the loss of its GPU device: after a single
 *          loss (a driver reset) it renders on Skia again on a new device;
 *          when every new device is lost as well (a driver that keeps
 *          failing), the window settles on GDI after a few recoveries instead
 *          of recreating its configuration again and again, and keeps
 *          painting. The loss is simulated (a real one, a TDR, needs an
 *          elevated shell). When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest transient
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaDeviceLossRecoveryTest persistent
 */
public class SkiaDeviceLossRecoveryTest {
    static final int SECONDS_AFTER_LOSS = 10;
    static final int MAX_CONFIGURATIONS = 5;   // three recoveries, and some slack

    public static void main(String[] args) throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        boolean persistent = args.length > 0 && args[0].equals("persistent");
        AtomicInteger paints = new AtomicInteger();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<Timer> timerRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("SkiaDeviceLossRecoveryTest");
            f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel p = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    int n = paints.incrementAndGet();
                    g.setColor((n & 1) == 0 ? Color.GREEN : new Color(0, 200, 0));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            f.setContentPane(p);
            f.setSize(300, 200);
            f.setLocation(100, 100);
            f.setVisible(true);
            Timer timer = new Timer(16, e -> p.repaint());
            timer.start();
            timerRef.set(timer);
            frameRef.set(f);
        });
        JFrame frame = frameRef.get();
        try {
            Thread.sleep(1500);
            String before = surface(frame);
            System.out.println("before the loss: " + before);
            if (!before.contains(".skia.")) {
                throw new RuntimeException("the window does not render on Skia: " + before);
            }
            int lastConfiguration = System.identityHashCode(frame.getGraphicsConfiguration());
            System.out.printf("configuration before the loss: %08x%n", lastConfiguration);
            Win32SKGraphicsConfig.simulateDeviceLoss(persistent);
            long lossAt = System.nanoTime();
            Set<Integer> configurations = new LinkedHashSet<>();   // the configurations the window gets after the loss
            List<String> changes = new ArrayList<>();
            long lastChangeAt = lossAt;
            String surface = before;
            while (System.nanoTime() - lossAt < SECONDS_AFTER_LOSS * 1_000_000_000L) {
                Thread.sleep(100);
                int gc = System.identityHashCode(frame.getGraphicsConfiguration());
                surface = surface(frame);
                if (gc != lastConfiguration) {
                    lastConfiguration = gc;
                    lastChangeAt = System.nanoTime();
                    configurations.add(gc);
                    changes.add(String.format("%.1f s: %08x %s", (lastChangeAt - lossAt) / 1e9, gc, surface));
                }
            }
            int paintsBefore = paints.get();
            Thread.sleep(500);
            int paintsInLast = paints.get() - paintsBefore;
            changes.forEach(c -> System.out.println("  " + c));
            double quietFor = (System.nanoTime() - lastChangeAt) / 1e9;
            System.out.printf("%d configurations after the loss, the last change %.1f s ago, surface %s, %d paints in 0.5 s%n",
                    configurations.size(), quietFor, surface, paintsInLast);
            if (paintsInLast == 0) {
                throw new RuntimeException("the window stopped painting");
            }
            if (configurations.isEmpty()) {
                throw new RuntimeException("the window kept its configuration: the loss was not recovered from");
            }
            if (configurations.size() > MAX_CONFIGURATIONS) {
                throw new RuntimeException(configurations.size() + " configurations after the loss: recovery does not settle");
            }
            if (quietFor < 3) {
                throw new RuntimeException("the configuration still changes " + SECONDS_AFTER_LOSS + " s after the loss");
            }
            if (persistent ? !surface.contains("GDIWindowSurfaceData") : !surface.contains(".skia.")) {
                throw new RuntimeException("expected the window on " + (persistent ? "GDI" : "Skia") + ", it is on " + surface);
            }
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                timerRef.get().stop();   // else the event thread keeps the VM alive
                frame.dispose();
            });
        }
    }

    static String surface(JFrame frame) {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(frame);
        SurfaceData sd = peer instanceof WComponentPeer w ? w.getSurfaceData() : null;
        return sd == null ? "none" : sd.getClass().getName();
    }
}
