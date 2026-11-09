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
 * @summary Regression test for JBR-10654: Graphite renders through Dawn unless
 *          sun.java2d.skia.graphite.api=vulkan asks for Vulkan, and a window shows what is painted.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.uiScale=1 SkiaGraphiteAPITest Dawn
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.graphite.api=dawn -Dsun.java2d.uiScale=1 SkiaGraphiteAPITest Dawn
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaGraphiteAPITest Vulkan
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.verbose=true -Dsun.java2d.skia.graphite.api=metal -Dsun.java2d.uiScale=1 SkiaGraphiteAPITest Dawn
 */

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Robot;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Native Vulkan is an evaluation option; a value the platform does not offer (metal on Windows)
 * leaves the default. On either API a window shows what is painted, also after a resize (a new
 * swap chain). Where the API asked for is not usable, SKGraphicsAPIFallback falls back to the
 * other one and says why (verbose): there is nothing to test then; a fallback it does not explain fails.
 */
public class SkiaGraphiteAPITest {

    /** How long a paint may take to reach the screen. */
    private static final int TIMEOUT_MS = 3000;

    /** How often the screen pixel is read while waiting. */
    private static final long POLL_INTERVAL_MS = 50;

    /** How far a channel of the screen pixel may be off the painted color (the display's color depth and rounding). */
    private static final int COLOR_TOLERANCE = 8;

    /** The window's size and position, and its size after the resize: nothing special, but away from the screen edge. */
    private static final int FRAME_WIDTH = 300;
    private static final int FRAME_HEIGHT = 200;
    private static final int FRAME_X = 100;
    private static final int FRAME_Y = 100;
    private static final int RESIZED_WIDTH = 420;
    private static final int RESIZED_HEIGHT = 260;

    /** A black square at the panel's origin, sampled at its center: the client area's origin lands at the window's. */
    private static final int MARKER_SIZE = 10;

    static volatile Color panelColor = Color.RED;

    public static void main(String[] args) throws Exception {
        String verbose = PipelineComparison.startCapturingVerbose();
        PipelineComparison.requireAPI(args[0], null, verbose);

        Robot robot = new Robot();
        AtomicReference<JFrame> frameReference = new AtomicReference<>();
        AtomicReference<JPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("SkiaGraphiteAPITest");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setUndecorated(true);
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    graphics.setColor(panelColor);
                    graphics.fillRect(0, 0, getWidth(), getHeight());
                    graphics.setColor(Color.BLACK);
                    graphics.fillRect(0, 0, MARKER_SIZE, MARKER_SIZE);
                }
            };
            frame.setContentPane(panel);
            frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
            frame.setLocation(FRAME_X, FRAME_Y);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frameReference.set(frame);
            panelReference.set(panel);
        });
        JFrame frame = frameReference.get();
        JPanel panel = panelReference.get();
        try {
            robot.waitForIdle();
            expect(robot, centerOf(panel), Color.RED, "the first paint");
            expect(robot, markerOf(panel), Color.BLACK, "the first paint's origin");

            panelColor = Color.GREEN;
            SwingUtilities.invokeAndWait(panel::repaint);
            expect(robot, centerOf(panel), Color.GREEN, "a repaint");

            panelColor = Color.BLUE;
            SwingUtilities.invokeAndWait(() -> frame.setSize(RESIZED_WIDTH, RESIZED_HEIGHT));
            robot.waitForIdle();
            expect(robot, centerOf(panel), Color.BLUE, "a repaint after a resize");
            expect(robot, markerOf(panel), Color.BLACK, "the origin after a resize");
            System.out.println("PASSED");
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    /** The screen point at the panel's center. */
    private static Point centerOf(JPanel panel) throws Exception {
        return onEventThread(() -> {
            Point origin = panel.getLocationOnScreen();
            return new Point(origin.x + panel.getWidth() / 2, origin.y + panel.getHeight() / 2);
        });
    }

    /** The screen point at the center of the marker square in the panel's corner. */
    private static Point markerOf(JPanel panel) throws Exception {
        return onEventThread(() -> {
            Point origin = panel.getLocationOnScreen();
            return new Point(origin.x + MARKER_SIZE / 2, origin.y + MARKER_SIZE / 2);
        });
    }

    private static Point onEventThread(Supplier<Point> pointOnScreen) throws Exception {
        AtomicReference<Point> pointReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> pointReference.set(pointOnScreen.get()));
        return pointReference.get();
    }

    /** Waits for the screen pixel to take the color, up to the timeout. */
    private static void expect(Robot robot, Point at, Color expected, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        Color seen = null;
        while (System.currentTimeMillis() < deadline) {
            seen = robot.getPixelColor(at.x, at.y);
            if (withinTolerance(seen, expected)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException(what + " did not reach the screen within " + TIMEOUT_MS + " ms: " + seen
                + " instead of " + expected);
    }

    private static boolean withinTolerance(Color seen, Color expected) {
        return Math.abs(seen.getRed() - expected.getRed()) <= COLOR_TOLERANCE
                && Math.abs(seen.getGreen() - expected.getGreen()) <= COLOR_TOLERANCE
                && Math.abs(seen.getBlue() - expected.getBlue()) <= COLOR_TOLERANCE;
    }
}
