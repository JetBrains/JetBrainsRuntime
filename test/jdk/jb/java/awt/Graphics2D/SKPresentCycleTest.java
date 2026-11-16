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
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JPanel;

import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: a Skia window is presented when its paint cycle ends (the
 *          peer's paint event, Swing's updateWindows), a SYNC or the flusher's timeout flush, never
 *          by a flush in the middle of a paint, so that it is not shown half-painted.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm/timeout=300 -Dsun.java2d.uiScale=1 SKPresentCycleTest
 */

/*
 * The windows are a child JVM's and the screen is read here: a Robot read syncs its own JVM's
 * toolkit (Toolkit.sync), which presents every window drawn into, so reading in the window's JVM
 * would hide a missed or an early present. The child shows an AWT frame and a Swing frame and
 * runs the parent's commands:
 * - STALL: the frame's next paint fills green, draws a modified software image (whose blit
 *   flushes the queue in the middle of the paint), then waits STALL_MS before it fills red. While
 *   it waits, the screen must still show the frame's previous content: a present at that flush
 *   would show the green. The wait stays below the flusher's timeout (100 ms after that flush),
 *   whose flush presents every window drawn into, a paint in progress too, as Direct3D's screen
 *   updates show a long paint partly.
 * - OUTSIDE: drawing on the frame outside any paint cycle (getGraphics) reaches the screen
 *   through the flusher's timeout flush.
 * - SWING: a Swing repaint reaches the screen (its paint cycle ends in updateWindows).
 */

/** What the child prints when Skia did not start, for the parent to skip on. */
private static final String NOT_ENABLED = "Skia is not enabled";

// the child's protocol: its announcements, the parent's commands
private static final String READY = "READY";
private static final String STALLED = "STALLED";
private static final String DONE = "DONE";
private static final String STALL = "STALL";
private static final String OUTSIDE = "OUTSIDE";
private static final String SWING = "SWING";
private static final String EXIT = "EXIT";

/** How long the stalled paint waits between its mid-paint flush and its last fill: below the timeout flush's 100 ms. */
private static final int STALL_MS = 50;
/** How long the parent watches the stalled paint once the child announced it: within the stall. */
private static final int WATCH_MS = 35;
/** How long a change may take to reach the screen (the timeout flush comes after 100 ms). */
private static final int REACH_TIMEOUT_MS = 5000;
private static final int POLL_MS = 20;
private static final long CHILD_TIMEOUT_S = 120;

private static final int CHANNEL_TOLERANCE = 8;

// the windows: undecorated, so that the content starts at the location
private static final int FRAME_X = 100, FRAME_Y = 100, SWING_X = 450;
private static final int WINDOW_SIZE = 300;
private static final int SPRITE_SIZE = 64;

// the child's state, set on the event thread
private static volatile boolean stallNextPaint;
private static volatile Color frameColor = Color.BLUE;
private static volatile Color panelColor = Color.GRAY;
private static final BufferedImage SPRITE = new BufferedImage(SPRITE_SIZE, SPRITE_SIZE, BufferedImage.TYPE_INT_RGB);

void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("child")) {
        child();
        return;
    }
    List<String> command = PipelineComparison.skiaChildCommand(List.of("-Dsun.java2d.uiScale=1"), getClass(), "child");
    Process child = ProcessTools.createTestJavaProcessBuilder(command).redirectErrorStream(true).start();
    try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
         Writer input = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) {
        String ready = awaitLine(output, READY);
        if (ready == null) {
            throw new RuntimeException("the child ended before it was ready");
        }
        if (ready.equals(NOT_ENABLED)) {
            throw new SkippedException("Skia is not enabled: nothing to test");
        }
        String[] coordinates = ready.split(" ");
        Point frameCenter = new Point(Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
        Point panelCenter = new Point(Integer.parseInt(coordinates[3]), Integer.parseInt(coordinates[4]));
        Robot robot = new Robot();
        expect(robot, frameCenter, Color.BLUE, "the frame's first paint");
        expect(robot, panelCenter, Color.GRAY, "the Swing frame's first paint");

        send(input, STALL);
        awaitLine(output, STALLED);
        long watchEnd = System.currentTimeMillis() + WATCH_MS;
        int reads = 0;
        while (System.currentTimeMillis() < watchEnd) {
            Color seen = robot.getPixelColor(frameCenter.x, frameCenter.y);
            reads++;
            if (close(seen, Color.GREEN)) {
                throw new RuntimeException("a flush in the middle of a paint presented the window: the screen shows "
                        + "the paint's first fill (green) after " + reads + " reads");
            }
        }
        if (reads == 0) {
            throw new RuntimeException("the screen was not read while the paint stalled");
        }
        System.out.println("the stalled paint: " + reads + " reads, none showed it half-painted");
        expect(robot, frameCenter, Color.RED, "the stalled paint's end");
        awaitLine(output, DONE);

        send(input, OUTSIDE);
        awaitLine(output, DONE);
        expect(robot, frameCenter, Color.YELLOW, "drawing outside a paint cycle");

        send(input, SWING);
        awaitLine(output, DONE);
        expect(robot, panelCenter, Color.MAGENTA, "a Swing repaint");

        send(input, EXIT);
        if (!child.waitFor(CHILD_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw new RuntimeException("the child did not exit");
        }
        if (child.exitValue() != 0) {
            throw new RuntimeException("the child exited with " + child.exitValue());
        }
    } finally {
        if (child.isAlive()) {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }
    }
    System.out.println("PASSED");
}

/** Echoes the child's output up to the line that starts with the prefix (or NOT_ENABLED), which it returns. */
private static String awaitLine(BufferedReader output, String prefix) throws Exception {
    String line;
    while ((line = output.readLine()) != null) {
        System.out.println("child: " + line);
        if (line.startsWith(prefix) || line.equals(NOT_ENABLED)) {
            return line;
        }
    }
    return null;
}

private static void send(Writer input, String command) throws Exception {
    input.write(command + "\n");
    input.flush();
}

/** Waits for the screen pixel to take the color, up to the timeout. */
private static void expect(Robot robot, Point at, Color expected, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + REACH_TIMEOUT_MS;
    Color seen = null;
    while (System.currentTimeMillis() < deadline) {
        seen = robot.getPixelColor(at.x, at.y);
        if (close(seen, expected)) {
            return;
        }
        //noinspection BusyWait (polls the screen, which signals no change)
        Thread.sleep(POLL_MS);
    }
    throw new RuntimeException(what + " did not reach the screen within " + REACH_TIMEOUT_MS + " ms: " + seen
            + " instead of " + expected);
}

private static boolean close(Color first, Color second) {
    return Math.abs(first.getRed() - second.getRed()) <= CHANNEL_TOLERANCE
            && Math.abs(first.getGreen() - second.getGreen()) <= CHANNEL_TOLERANCE
            && Math.abs(first.getBlue() - second.getBlue()) <= CHANNEL_TOLERANCE;
}

private void child() throws Exception {
    if (!SKEnvironment.isSkiaEnabled()) {
        System.out.println(NOT_ENABLED);
        return;
    }
    Frame frame = new Frame("SKPresentCycleTest") {
        @Override
        public void update(Graphics graphics) {
            paint(graphics);   // the paint covers the window
        }

        @Override
        public void paint(Graphics graphics) {
            if (stallNextPaint) {
                stallNextPaint = false;
                graphics.setColor(Color.GREEN);
                graphics.fillRect(0, 0, getWidth(), getHeight());
                SPRITE.setRGB(0, 0, SPRITE.getRGB(0, 0) + 1);   // modified: its blit uploads it and flushes the queue
                graphics.drawImage(SPRITE, 0, 0, null);
                System.out.println(STALLED);
                try {
                    Thread.sleep(STALL_MS);
                } catch (InterruptedException exception) {
                    throw new RuntimeException(exception);
                }
                frameColor = Color.RED;
            }
            graphics.setColor(frameColor);
            graphics.fillRect(0, 0, getWidth(), getHeight());
        }
    };
    JPanel panel = new JPanel() {
        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(panelColor);
            graphics.fillRect(0, 0, getWidth(), getHeight());
        }
    };
    JFrame swingFrame = new JFrame("SKPresentCycleTest Swing");
    EventQueue.invokeAndWait(() -> {
        frame.setUndecorated(true);
        frame.setBackground(Color.BLUE);
        frame.setBounds(FRAME_X, FRAME_Y, WINDOW_SIZE, WINDOW_SIZE);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        swingFrame.setUndecorated(true);
        swingFrame.setContentPane(panel);
        swingFrame.setBounds(SWING_X, FRAME_Y, WINDOW_SIZE, WINDOW_SIZE);
        swingFrame.setAlwaysOnTop(true);
        swingFrame.setVisible(true);
    });
    Robot robot = new Robot();
    robot.waitForIdle();
    Point frameOrigin = frame.getLocationOnScreen();
    Point panelOrigin = panel.getLocationOnScreen();
    System.out.println(READY + " " + (frameOrigin.x + WINDOW_SIZE / 2) + " " + (frameOrigin.y + WINDOW_SIZE / 2)
            + " " + (panelOrigin.x + panel.getWidth() / 2) + " " + (panelOrigin.y + panel.getHeight() / 2));
    BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String command;
    while ((command = commands.readLine()) != null && !command.equals(EXIT)) {
        switch (command) {
            case STALL -> {
                stallNextPaint = true;
                frame.repaint();
                // the paint is done once the event queue ran it
                EventQueue.invokeAndWait(() -> { });
            }
            case OUTSIDE -> EventQueue.invokeAndWait(() -> {
                Graphics graphics = frame.getGraphics();
                graphics.setColor(Color.YELLOW);
                graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
                graphics.dispose();
            });
            case SWING -> EventQueue.invokeAndWait(() -> {
                panelColor = Color.MAGENTA;
                panel.repaint();
            });
            default -> throw new RuntimeException("unknown command " + command);
        }
        System.out.println(DONE);
    }
    EventQueue.invokeAndWait(() -> {
        frame.dispose();
        swingFrame.dispose();
    });
    System.exit(0);
}
