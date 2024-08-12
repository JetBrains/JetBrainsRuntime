/*
 * Copyright 2024 JetBrains s.r.o.
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.JFrame;
import javax.swing.PopupFactory;
import javax.swing.WindowConstants;
import java.awt.Cursor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Window;
import sun.lwawt.macosx.CPlatformWindow;

/*
 * @test
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx:+open java.desktop/sun.awt
 * @summary Test mouse entered/exit events on macOS with rounded corners
 * @run main MouseEnterExitMacRoundedCornersTest
 */
public class MouseEnterExitMacRoundedCornersTest {

    private static MouseCounter windowMouseListener;
    private static MouseCounter popupMouseListener;
    private static Window window;
    private static Window popupWindow;
    private volatile static Point popupAt;

    private static void showWindow() {
        window = new JFrame();
        windowMouseListener = new MouseCounter();
        window.addMouseListener(windowMouseListener);
        window.setSize(400, 300);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                showPopup(window);
            }
        });
        window.setVisible(true);
    }

    private static void showPopup(Component owner) {
        var factory = PopupFactory.getSharedInstance();
        var helloWorld = new JLabel();
        helloWorld.setPreferredSize(new Dimension(200, 100));
        helloWorld.setOpaque(true);
        var popup = factory.getPopup(owner, helloWorld, 100, 100);
        var window = getWindowParent(helloWorld);
        popupMouseListener = new MouseCounter();
        window.addMouseListener(popupMouseListener);
        setRoundedCorners(window, new Object[] {8.0f, 1, Color.BLACK});
        popupWindow = window;
        popup.show();
    }

    private static void setRoundedCorners(Window window, Object[] args) {
        try {
            var method = CPlatformWindow.class.getDeclaredMethod("setRoundedCorners", Window.class, Object.class);
            method.setAccessible(true);
            method.invoke(null, window, args);
        } catch (java.lang.Exception e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    private static Window getWindowParent(JLabel helloWorld) {
        Component window = helloWorld;
        while (window != null) {
            window = window.getParent();
            if (window instanceof Window) {
                break;
            }
        }
        return (Window) window;
    }

    public static void main(String[] args) throws Exception {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(100);
            robot.setAutoWaitForIdle(true);

            EventQueue.invokeAndWait(MouseEnterExitMacRoundedCornersTest::showWindow);
            robot.waitForIdle();

            EventQueue.invokeAndWait(() -> {
                popupAt = popupWindow.getLocationOnScreen();
            });

            int popupEnterPoint = findPoint(robot, popupAt, -5, +10, () -> popupMouseListener.enteredCount.get() > 0);
            moveBackAndForth(robot, popupAt, -5, popupEnterPoint);

            int frameEnterPoint = findPoint(robot, popupAt, +10, -5, () -> windowMouseListener.enteredCount.get() > 0);
            moveBackAndForth(robot, popupAt, +10, frameEnterPoint);

            System.out.println("Test PASSED");
        } finally {
            EventQueue.invokeAndWait(() -> {
                if (window != null) {
                    window.dispose();
                }
                if (popupWindow != null) {
                    popupWindow.dispose();
                }
            });
        }
    }

    private static int findPoint(Robot robot, Point popupAt, int start, int end, Supplier<Boolean> condition) throws Exception {
        robot.mouseMove(popupAt.x + start, popupAt.y + start);
        robot.waitForIdle();
        resetCounters();
        return move(robot, popupAt, start, end, condition);
    }

    private static void moveBackAndForth(Robot robot, Point popupAt, int start, int end) throws Exception {
        robot.mouseMove(popupAt.x + start, popupAt.y + start);
        robot.waitForIdle();
        resetCounters();
        move(robot, popupAt, start, end, null);
        move(robot, popupAt, end, start, null);
        assertEnteredExited();
    }

    private static void resetCounters() {
        windowMouseListener.enteredCount.set(0);
        windowMouseListener.exitedCount.set(0);
        popupMouseListener.enteredCount.set(0);
        popupMouseListener.exitedCount.set(0);
    }

    private static int move(Robot robot, Point popupAt, int start, int end, Supplier<Boolean> condition) throws Exception {
        for (int offset = start; start < end ? offset <= end : offset >= end; offset = (start < end ? offset + 1 : offset - 1)) {
            if (condition != null && condition.get()) {
                return offset;
            }
            robot.mouseMove(popupAt.x + offset, popupAt.y + offset);
        }
        return -1;
    }

    private static void assertEnteredExited() {
        var enteredWindow = windowMouseListener.enteredCount.get();
        var exitedWindow = windowMouseListener.exitedCount.get();
        var enteredPopup = popupMouseListener.enteredCount.get();
        var exitedPopup = popupMouseListener.exitedCount.get();
        if (enteredWindow != 1 || exitedWindow != 1 || enteredPopup != 1 || exitedPopup != 1) {
            throw new RuntimeException(
                    "Moved the mouse back and forth, expected to enter/exit both the popup and window exactly once, but got: "
                            + "enteredWindow=" + enteredWindow + ", exitedWindow=" + exitedWindow + ", "
                            + "enteredPopup=" + enteredPopup + ", exitedPopup=" + exitedPopup
            );
        }
    }

    private static class MouseCounter extends MouseAdapter {
        final AtomicInteger enteredCount = new AtomicInteger();
        final AtomicInteger exitedCount = new AtomicInteger();

        @Override
        public void mouseEntered(MouseEvent e) {
            enteredCount.incrementAndGet();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            exitedCount.incrementAndGet();
        }
    };
}
