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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 * @requires (os.family == "windows")
 * @summary JBR-2866 - Tests that HwFacade window used in IDEA does not bring to front when shown.
 * @author Anton Tarasov
 * @run main/othervm HwFacadeWindowNoFrontTest
 */
 public class HwFacadeWindowNoFrontTest {
    static final Color TRANSPARENT_COLOR = new Color(1, 1, 1, 0);
    static final Color BG_COLOR = Color.red;

    static JFrame bottomFrame = new JFrame("bottom frame");
    static JFrame topFrame = new JFrame("top frame");
    static JWindow window = new JWindow(bottomFrame);

    public static void main(String[] args) throws InterruptedException, AWTException, InvocationTargetException {
        EventQueue.invokeLater(HwFacadeWindowNoFrontTest::showGUI);
        awaitVisibilityChanged(window, true);

        Robot robot = new Robot();

        // 1) Check window is below top frame
        Color pixel = robot.getPixelColor(topFrame.getX() + 100, topFrame.getY() + 100);
        if (pixel.equals(BG_COLOR)) {
            throw new RuntimeException("Test FAILED");
        }

        EventQueue.invokeLater(() -> topFrame.dispose());
        awaitVisibilityChanged(topFrame, false);

        // 2) Check window is above bottom frame
        pixel = robot.getPixelColor(bottomFrame.getX() + 100, bottomFrame.getY() + 100);
        if (!pixel.equals(BG_COLOR)) {
            throw new RuntimeException("Test FAILED");
        }

        EventQueue.invokeLater(() -> {
            bottomFrame.dispose();
            window.dispose();
        });

        System.out.println("Test PASSED");
    }

    static void showGUI() {
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
        // window should appear above bottomFrame, below topFrame
        window.setVisible(true);
    }

    private static void awaitVisibilityChanged(Window window, boolean shown) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        checkVisibilityChanged(window, latch, shown);
        latch.await(2, TimeUnit.SECONDS);
    }

    private static void checkVisibilityChanged(Window window, CountDownLatch latch, boolean shown) {
        EventQueue.invokeLater(() -> {
            try {
                window.getLocationOnScreen();
            } catch (IllegalComponentStateException ignored) {
                if (shown) {
                    checkVisibilityChanged(window, latch, shown);
                    return;
                }
            }
            if (!shown) {
                checkVisibilityChanged(window, latch, shown);
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
}
