/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @test
 * @key headful
 * @requires (os.family == "windows") & (os.arch == "amd64" | os.arch == "x86_64")
 * @summary JBR-2866 - Tests that HwFacade window used in IDEA does not bring to front when shown.
 * @author Anton Tarasov
 * @run main/othervm HwFacadeWindowNoFrontTest
 */
 public class HwFacadeWindowNoFrontTest {
    static final Color TRANSPARENT_COLOR = new Color(1, 1, 1, 0);
    static final Color BG_COLOR = Color.red;

    static volatile JFrame bottomFrame;
    static volatile JFrame topFrame;
    static volatile JWindow window;

    public static void main(String[] args) throws InterruptedException, AWTException, InvocationTargetException {
        performAndWait(latch -> EventQueue.invokeLater(() -> showGUI(latch)), "initializing GUI");
        performAndWait(latch -> waitVisibilityChanged(window, latch, true), "waiting for the window to show");

        Robot robot = new Robot();

        //
        // 1) Check window is below top frame
        //
        performAndRepeat(10, robot, "window is not below the top frame", () -> {
            Color pixel = robot.getPixelColor(topFrame.getX() + 100, topFrame.getY() + 100);
            return !pixel.equals(BG_COLOR);
        });

        EventQueue.invokeLater(() -> topFrame.dispose());
        performAndWait(latch -> waitVisibilityChanged(topFrame, latch, false), "waiting for the top frame to dispose");

        //
        // 2) Check window is above bottom frame
        //
        performAndRepeat(10, robot, "window is not above the bottom frame", () -> {
            Color pixel = robot.getPixelColor(bottomFrame.getX() + 100, bottomFrame.getY() + 100);
            return pixel.equals(BG_COLOR);
        });

        EventQueue.invokeLater(() -> {
            bottomFrame.dispose();
            window.dispose();
        });

        System.out.println("Test PASSED");
    }

    static void showGUI(CountDownLatch latch) {
        bottomFrame = new JFrame("frame1");
        bottomFrame.setSize(400, 200);
        bottomFrame.setLocationRelativeTo(null);

        window = new JWindow(bottomFrame);
        window.add(new JPanel() {
            {
                setBackground(TRANSPARENT_COLOR);
            }
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(BG_COLOR);
                g.fillRect(0, 0, window.getWidth(), window.getHeight());
            }
        });
        setIgnoreMouseEvents(window);
        window.setBackground(TRANSPARENT_COLOR);
        window.setSize(500, 300);
        window.setLocationRelativeTo(null);

        topFrame = new JFrame("frame2");
        topFrame.setSize(400, 200);
        topFrame.setLocationRelativeTo(null);

        // the order matters
        bottomFrame.setVisible(true);
        topFrame.setVisible(true);
        window.setVisible(true);

        latch.countDown();
    }

    private static void waitVisibilityChanged(Window window, CountDownLatch latch, boolean shown) {
        EventQueue.invokeLater(() -> {
            boolean locationRetrieved = false;
            try {
                locationRetrieved = window.getLocationOnScreen() != null;
            } catch (IllegalComponentStateException ignored) {
            }
            if (shown == !locationRetrieved) {
                waitVisibilityChanged(window, latch, shown);
                return;
            }
            latch.countDown();
        });
    }

    private static void setIgnoreMouseEvents(Window window) {
        window.setEnabled(false);
        try {
            Method m = Window.class.getDeclaredMethod("setIgnoreMouseEvents", boolean.class);
            m.setAccessible(true);
            m.invoke(window, true);
        } catch (Throwable ignore) {
        }
    }

    private static void performAndWait(Consumer<CountDownLatch> performer, String errMsg) {
        CountDownLatch latch = new CountDownLatch(1);
        performer.accept(latch);
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test ERROR: " + errMsg);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static void performAndRepeat(int count, Robot robot, String errMsg, Callable<Boolean> action) {
        while (count-- >= 0) {
            try {
                if (action.call()) return;
                robot.delay(50);
            } catch (Exception ignored) {
            }
        }
        throw new RuntimeException("Test ERROR: " + errMsg);
    }
}
