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

import com.apple.eawt.Application;
import com.apple.eawt.FullScreenAdapter;
import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.event.FullScreenEvent;
import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-10637 Mouse events are swallowed below the menu bar
 *          in native full screen with custom title bar and apple.awt.newFullScreenControls=true
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 * @run main/othervm -Dapple.awt.newFullScreenControls=true NativeFullScreenTitleBarMouseEvents
 */

public class NativeFullScreenTitleBarMouseEvents {
    private static final float TITLE_BAR_HEIGHT = 40;
    // offsets (in pixels) below the menu bar, inside the ~28px strip occupied by the native full screen title bar
    private static final int[] STRIP_OFFSETS = {4, 10, 16, 22};
    private static final int DEFAULT_MENU_BAR_HEIGHT = 24;

    private static final CompletableFuture<Boolean> frameShown = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> enteredFullScreen = new CompletableFuture<>();
    private static volatile CountDownLatch eventsLatch;

    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        if (!JBR.isWindowDecorationsSupported()) {
            System.out.println("Custom window decorations aren't supported, skipping the test");
            return;
        }
        try {
            SwingUtilities.invokeAndWait(NativeFullScreenTitleBarMouseEvents::initUI);
            frameShown.get(5, TimeUnit.SECONDS);

            Robot robot = new Robot();
            robot.delay(1000);

            GraphicsConfiguration gc = frame.getGraphicsConfiguration();
            Rectangle screen = gc.getBounds();
            int menuBarHeight = Toolkit.getDefaultToolkit().getScreenInsets(gc).top;
            if (menuBarHeight <= 0) {
                menuBarHeight = DEFAULT_MENU_BAR_HEIGHT;
            }
            System.out.println("Screen bounds: " + screen + ", menu bar height: " + menuBarHeight);

            SwingUtilities.invokeAndWait(() -> Application.getApplication().requestToggleFullScreen(frame));
            enteredFullScreen.get(10, TimeUnit.SECONDS);
            robot.delay(2000); // wait for full screen transition animation to finish
            robot.waitForIdle();

            int x = screen.x + screen.width / 2;

            // sanity check: events in the middle of the window must be delivered
            if (!clickAndWait(robot, x, screen.y + screen.height / 2, false)) {
                throw new RuntimeException("Mouse events aren't delivered to the full screen window at all");
            }

            List<Integer> failedPoints = new ArrayList<>();
            for (int offset : STRIP_OFFSETS) {
                int y = screen.y + menuBarHeight + offset;
                if (!clickAndWait(robot, x, y, true)) {
                    failedPoints.add(y);
                }
            }
            if (!failedPoints.isEmpty()) {
                throw new RuntimeException("Mouse events below the revealed menu bar weren't delivered to the window " +
                        "at x=" + x + ", y=" + failedPoints);
            }
        } finally {
            SwingUtilities.invokeAndWait(NativeFullScreenTitleBarMouseEvents::disposeUI);
        }
    }

    private static boolean clickAndWait(Robot robot, int x, int y, boolean revealMenuBar) throws Exception {
        if (revealMenuBar) {
            // moving the pointer to the top edge reveals the menu bar and the native full screen title bar
            Rectangle screen = frame.getGraphicsConfiguration().getBounds();
            robot.mouseMove(x, screen.y);
            robot.delay(2000);
        }
        eventsLatch = new CountDownLatch(2);
        robot.mouseMove(x, y);
        robot.delay(300);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        boolean received = eventsLatch.await(2, TimeUnit.SECONDS);
        System.out.println("Click at (" + x + ", " + y + ")" + (revealMenuBar ? " with revealed menu bar" : "") +
                ": " + (received ? "received" : "NOT received"));
        return received;
    }

    private static void initUI() {
        frame = new JFrame("NativeFullScreenTitleBarMouseEvents");
        JPanel panel = new JPanel();
        panel.setBackground(Color.GREEN);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                System.out.println("Pressed at " + e.getLocationOnScreen());
                CountDownLatch latch = eventsLatch;
                if (latch != null) latch.countDown();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                System.out.println("Released at " + e.getLocationOnScreen());
                CountDownLatch latch = eventsLatch;
                if (latch != null) latch.countDown();
            }
        });
        frame.setContentPane(panel);
        frame.setBounds(200, 200, 600, 400);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                frameShown.complete(true);
            }
        });
        FullScreenUtilities.addFullScreenListenerTo(frame, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                enteredFullScreen.complete(true);
            }
        });

        WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
        titleBar.setHeight(TITLE_BAR_HEIGHT);
        JBR.getWindowDecorations().setCustomTitleBar(frame, titleBar);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }
}
