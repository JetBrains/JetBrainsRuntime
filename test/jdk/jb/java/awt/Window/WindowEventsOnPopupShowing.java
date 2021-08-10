/*
 * Copyright 2000-2021 JetBrains s.r.o.
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
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @test
 * @summary Regression test for JBR-3676 WINDOW_ACTIVATED/DEACTIVATED events
 *          sent to a frame when child window closes on macOS
 * @key headful
 */

public class WindowEventsOnPopupShowing {
    private static final AtomicInteger events = new AtomicInteger();
    private static Robot robot;
    private static JFrame frame;
    private static JButton openButton;
    private static JButton closeButton;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(WindowEventsOnPopupShowing::initUI);
            robot.delay(1000); // wait for frame to be shown
            events.set(0);
            clickAt(openButton);
            robot.delay(1000); // wait for popup to be shown
            clickAt(closeButton);
            robot.delay(1000); // wait for popup to be closed
            int eventCount = events.get();
            if (eventCount != 0) {
                throw new RuntimeException("Unexpected events received: " + eventCount);
            }
        }
        finally {
            SwingUtilities.invokeAndWait(WindowEventsOnPopupShowing::disposeUI);
        }
    }

    private static void initUI() {
        openButton = new JButton("Open popup");
        closeButton = new JButton("Close");

        frame = new JFrame("WindowEventsOnPopupShowing");
        frame.add(openButton);
        frame.pack();

        JWindow popup = new JWindow(frame);
        popup.add(closeButton);
        popup.pack();

        openButton.addActionListener(e -> popup.setVisible(true));
        closeButton.addActionListener(e -> popup.dispose());

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                events.incrementAndGet();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                events.incrementAndGet();
            }
        });

        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}