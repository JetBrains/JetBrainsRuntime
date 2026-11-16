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

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import jtreg.SkippedException;

/*
 * @test
 * @key headful multimon
 * @summary Regression test for JBR-10654: a Skia window moved to another screen renders there,
 *          also after a resize, and again when moved back.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.awt java.desktop/sun.awt.windows java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SKScreenMoveTest
 */

/*
 * Skia took the default screen only, and a window moved to another screen got a GDI surface
 * there; GDI cannot draw into a window that presented through a flip-model swap chain, so the
 * window kept showing its last Skia frame (stretched on resize) until it was moved back. The
 * window visits every screen and returns to the default one, and on each screen it has to be on
 * Skia and show what it paints, before and after a resize. Skipped with one screen.
 */

// how long the window may take to move, and a screen pixel to show what the window paints
private static final long TIMEOUT_MS = 5000;
private static final long POLL_INTERVAL_MS = 50;
private static final int COLOR_TOLERANCE = 8;

private static final int FRAME_OFFSET = 50;
private static final int FRAME_WIDTH = 240;
private static final int FRAME_HEIGHT = 160;
private static final int RESIZED_WIDTH = 300;
private static final Color[] COLORS = { Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN, Color.ORANGE };

private static volatile Color fill = Color.RED;
private static Frame frame;

void main() throws Exception {
    PipelineComparison.requireSkia();
    GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
    GraphicsDevice[] screens = graphicsEnvironment.getScreenDevices();
    if (screens.length < 2) {
        throw new SkippedException("needs two screens, there is " + screens.length);
    }
    GraphicsDevice defaultScreen = graphicsEnvironment.getDefaultScreenDevice();
    List<GraphicsDevice> visits = new ArrayList<>();
    visits.add(defaultScreen);
    for (GraphicsDevice screen : screens) {
        if (screen != defaultScreen) {
            visits.add(screen);
        }
    }
    visits.add(defaultScreen);
    EventQueue.invokeAndWait(() -> {
        frame = new Frame("SKScreenMoveTest") {
            @Override
            public void paint(Graphics graphics) {
                graphics.setColor(fill);
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
    });
    try {
        int step = 0;
        for (GraphicsDevice screen : visits) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();
            EventQueue.invokeAndWait(() -> {
                frame.setBounds(bounds.x + FRAME_OFFSET, bounds.y + FRAME_OFFSET, FRAME_WIDTH, FRAME_HEIGHT);
                frame.setVisible(true);
            });
            awaitScreen(screen);
            paintAndExpect(COLORS[step++ % COLORS.length], "on " + screen.getIDstring());
            EventQueue.invokeAndWait(() -> frame.setSize(RESIZED_WIDTH, FRAME_HEIGHT));
            paintAndExpect(COLORS[step++ % COLORS.length], "resized on " + screen.getIDstring());
        }
        System.out.println("PASSED");
    } finally {
        EventQueue.invokeAndWait(frame::dispose);
    }
}

/** Waits for the window to be on the screen's configuration, with a Skia surface. */
private void awaitScreen(GraphicsDevice screen) throws Exception {
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (frame.getGraphicsConfiguration().getDevice() != screen && System.currentTimeMillis() < deadline) {
        //noinspection BusyWait (polls the window configuration until the deadline)
        Thread.sleep(POLL_INTERVAL_MS);
    }
    if (frame.getGraphicsConfiguration().getDevice() != screen) {
        throw new RuntimeException("the window did not move to " + screen.getIDstring());
    }
    String surface = WindowSurfaces.await(frame, s -> s.contains(PipelineComparison.SKIA_PACKAGE), TIMEOUT_MS);
    System.out.println(screen.getIDstring() + ": " + frame.getGraphicsConfiguration().getClass().getName()
            + ", surface " + surface);
    if (!surface.contains(PipelineComparison.SKIA_PACKAGE)) {
        throw new RuntimeException("the window is not on Skia on " + screen.getIDstring() + ": " + surface);
    }
}

/** Repaints the window with the color and waits for its center to show it. */
private void paintAndExpect(Color color, String what) throws Exception {
    fill = color;
    EventQueue.invokeAndWait(frame::repaint);
    Color seen = WindowSurfaces.awaitCenterColor(frame, pixel -> WindowSurfaces.near(pixel, color, COLOR_TOLERANCE), TIMEOUT_MS);
    if (!WindowSurfaces.near(seen, color, COLOR_TOLERANCE)) {
        throw new RuntimeException(what + ": the window shows " + seen + " instead of " + color
                + " (a window GDI took over from Skia keeps its last frame)");
    }
    System.out.println(what + ": " + seen);
}
