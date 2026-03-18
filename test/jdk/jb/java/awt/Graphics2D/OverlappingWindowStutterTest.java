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

/*
 * @test
 * @summary Verifies no EDT starvation from RenderQueue lock contention when
 *          one window is geometrically contained within another (Metal pipeline).
 *          The test creates two JFrames — a large outer frame and a small inner
 *          frame fully contained within it — both continuously repainting. An EDT
 *          latency monitor detects stalls caused by MTLLayer.blitTexture holding
 *          the RenderQueue lock for too long.
 * @requires (os.family == "mac")
 * @run main/timeout=90/othervm OverlappingWindowStutterTest
 */

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

public class OverlappingWindowStutterTest {

    // Configurable via system properties
    static final int TEST_DURATION_MS =
            Integer.getInteger("test.duration", 60_000);
    static final int STALL_THRESHOLD_MS =
            Integer.getInteger("test.threshold", 500);
    // Warm-up period: ignore stalls during initial window setup
    static final int WARMUP_MS = 3_000;

    // EDT latency tracking
    static final AtomicLong maxStallMs = new AtomicLong(0);
    static final AtomicLong stallCount = new AtomicLong(0);
    static final AtomicReference<String> worstStallInfo =
            new AtomicReference<>("");

    // Repaint counter (for liveness check)
    static final AtomicLong outerPaintCount = new AtomicLong(0);
    static final AtomicLong innerPaintCount = new AtomicLong(0);

    static volatile boolean warmupDone = false;
    static volatile boolean testRunning = true;

    public static void main(String[] args) throws Exception {
        System.out.println("=== OverlappingWindowStutterTest ===");
        System.out.println("Metal enabled: " +
                !"false".equals(System.getProperty("sun.java2d.metal")));
        System.out.println("Test duration: " + TEST_DURATION_MS + " ms");
        System.out.println("Stall threshold: " + STALL_THRESHOLD_MS + " ms");
        System.out.println();

        CountDownLatch setupDone = new CountDownLatch(1);
        AtomicReference<JFrame> outerRef = new AtomicReference<>();
        AtomicReference<JFrame> innerRef = new AtomicReference<>();

        // Create windows on EDT
        SwingUtilities.invokeLater(() -> {
            try {
                createAndShowWindows(outerRef, innerRef);
            } finally {
                setupDone.countDown();
            }
        });

        if (!setupDone.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for window setup");
        }

        // Let windows stabilize
        Thread.sleep(WARMUP_MS);
        warmupDone = true;
        System.out.println("[" + WARMUP_MS + " ms] Warm-up complete. " +
                "Monitoring EDT latency...");

        // Start EDT latency probe
        Timer latencyProbe = startLatencyProbe();

        // Start focus-switching timer (simulates user switching between windows)
        Timer focusSwitcher = startFocusSwitcher(outerRef, innerRef);

        // Run for the configured duration
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < TEST_DURATION_MS) {
            Thread.sleep(1_000);
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("[%5d ms] outer paints=%d, inner paints=%d, " +
                            "max stall=%d ms, stalls>%dms=%d%n",
                    elapsed,
                    outerPaintCount.get(), innerPaintCount.get(),
                    maxStallMs.get(), STALL_THRESHOLD_MS, stallCount.get());
        }

        // Stop
        testRunning = false;
        latencyProbe.stop();
        focusSwitcher.stop();

        // Dispose windows on EDT
        CountDownLatch disposeDone = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                JFrame outer = outerRef.get();
                JFrame inner = innerRef.get();
                if (inner != null) inner.dispose();
                if (outer != null) outer.dispose();
            } finally {
                disposeDone.countDown();
            }
        });
        disposeDone.await(5, TimeUnit.SECONDS);

        // Report
        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("Max EDT stall: " + maxStallMs.get() + " ms");
        System.out.println("Stalls > " + STALL_THRESHOLD_MS +
                " ms: " + stallCount.get());
        System.out.println("Outer paint count: " + outerPaintCount.get());
        System.out.println("Inner paint count: " + innerPaintCount.get());

        if (maxStallMs.get() > STALL_THRESHOLD_MS) {
            System.out.println("Worst stall: " + worstStallInfo.get());
            throw new RuntimeException(
                    "FAIL: EDT stall of " + maxStallMs.get() +
                            " ms detected (threshold: " + STALL_THRESHOLD_MS +
                            " ms). This indicates RenderQueue lock contention " +
                            "from MTLLayer.blitTexture during overlapping-window " +
                            "compositing. See JBR-9916.");
        }

        // Liveness check: both windows must have painted
        if (outerPaintCount.get() < 10 || innerPaintCount.get() < 10) {
            throw new RuntimeException(
                    "FAIL: Insufficient paint count (outer=" +
                            outerPaintCount.get() + ", inner=" +
                            innerPaintCount.get() + "). Windows may not " +
                            "have rendered properly.");
        }

        System.out.println("PASS");
    }

    static void createAndShowWindows(AtomicReference<JFrame> outerRef,
                                     AtomicReference<JFrame> innerRef) {
        // Get screen size for positioning
        GraphicsEnvironment ge = GraphicsEnvironment
                .getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        Rectangle screenBounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

        int availW = screenBounds.width - insets.left - insets.right;
        int availH = screenBounds.height - insets.top - insets.bottom;

        // Outer frame: ~80% of screen
        int outerW = (int) (availW * 0.8);
        int outerH = (int) (availH * 0.8);
        int outerX = insets.left + (availW - outerW) / 2;
        int outerY = insets.top + (availH - outerH) / 2;

        // Inner frame: ~30% of screen, centered within outer
        int innerW = (int) (availW * 0.3);
        int innerH = (int) (availH * 0.3);
        int innerX = outerX + (outerW - innerW) / 2;
        int innerY = outerY + (outerH - innerH) / 2;

        // Create outer frame
        JFrame outer = new JFrame("JBR-9916 Outer Window");
        outer.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        outer.setBounds(outerX, outerY, outerW, outerH);
        outer.setContentPane(new AnimatedPanel(outerPaintCount, Color.WHITE,
                "Outer Window — continuous repaint"));
        outer.setVisible(true);
        outerRef.set(outer);

        // Create inner frame
        JFrame inner = new JFrame("JBR-9916 Inner Window");
        inner.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        inner.setBounds(innerX, innerY, innerW, innerH);
        inner.setContentPane(new AnimatedPanel(innerPaintCount,
                new Color(240, 245, 255),
                "Inner Window — continuous repaint"));
        inner.setVisible(true);
        innerRef.set(inner);
    }

    /**
     * A panel that continuously repaints itself, simulating active IDE content.
     * Each paint increments a counter and draws animated content to force
     * the Metal pipeline to blit every frame.
     */
    static class AnimatedPanel extends JPanel {
        private final AtomicLong paintCounter;
        private final String label;
        private int tick = 0;
        private final Timer animTimer;

        AnimatedPanel(AtomicLong paintCounter, Color bg, String label) {
            this.paintCounter = paintCounter;
            this.label = label;
            setBackground(bg);
            setOpaque(true);

            // Repaint at ~60 fps
            animTimer = new Timer(16, e -> {
                tick++;
                repaint();
            });
            animTimer.setCoalesce(true);
            animTimer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintCounter.incrementAndGet();

            int w = getWidth();
            int h = getHeight();

            // Draw animated rectangles to force real rendering work
            // (not just a no-op
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // Moving gradient bar
            int barY = (tick * 3) % Math.max(h, 1);
            g2.setColor(new Color(100, 150, 200, 80));
            g2.fillRect(0, barY, w, 40);

            // Label
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g2.drawString(label, 20, 30);
            g2.drawString("Paint #" + paintCounter.get() +
                    "  tick=" + tick, 20, 50);

            // Grid of small rects to generate fill operations
            // (these go through BufferedRenderPipe.fillParallelogram
            // which is the EDT path that contends with blitTexture)
            g2.setColor(new Color(180, 180, 220, 40));
            int cols = 10;
            int rows = 8;
            int cellW = w / cols;
            int cellH = h / rows;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int x = c * cellW + (tick + r + c) % 5;
                    int y = r * cellH + (tick + r) % 5;
                    g2.fillRect(x + 2, y + 2, cellW - 4, cellH - 4);
                }
            }
        }
    }

    /**
     * Periodically measures EDT responsiveness. Schedules an invokeLater
     * task and measures how long until it executes. High latency means
     * the EDT was blocked (likely on RenderQueue.lock).
     */
    static Timer startLatencyProbe() {
        Timer t = new Timer(50, new ActionListener() {
            long scheduledAt;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (!testRunning) return;
                scheduledAt = System.nanoTime();
                SwingUtilities.invokeLater(() -> {
                    if (!warmupDone || !testRunning) return;
                    long latencyNs = System.nanoTime() - scheduledAt;
                    long latencyMs = latencyNs / 1_000_000;
                    if (latencyMs > STALL_THRESHOLD_MS) {
                        stallCount.incrementAndGet();
                    }
                    long prev = maxStallMs.get();
                    if (latencyMs > prev) {
                        maxStallMs.set(latencyMs);
                        worstStallInfo.set(String.format(
                                "EDT stall %d ms at t=%d ms (probe " +
                                        "scheduled→executed latency)",
                                latencyMs,
                                TimeUnit.NANOSECONDS.toMillis(
                                        System.nanoTime())));
                    }
                });
            }
        });
        t.setCoalesce(false); // don't coalesce — we need each probe
        t.start();
        return t;
    }

    /**
     * Periodically switches focus between outer and inner window.
     * This mimics the user switching between overlapping project windows,
     * which triggers repaints of the inactive window and is the key
     * trigger condition for the stutter.
     */
    static Timer startFocusSwitcher(AtomicReference<JFrame> outerRef,
                                    AtomicReference<JFrame> innerRef) {
        Timer t = new Timer(800, new ActionListener() {
            boolean focusOuter = true;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (!testRunning) return;
                JFrame target = focusOuter ?
                        outerRef.get() : innerRef.get();
                if (target != null) {
                    target.toFront();
                    target.requestFocus();
                }
                focusOuter = !focusOuter;
            }
        });
        t.start();
        return t;
    }
}
