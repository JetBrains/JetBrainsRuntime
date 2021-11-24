/*
 * Copyright 2021 JetBrains s.r.o.
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
 * @summary Regression test for JBR-4021 Unexpected focus event order on window showing
 * @key headful
 */

public class EventsOnPopupShowing {
    private static final AtomicInteger gainedCount = new AtomicInteger();

    private static Robot robot;
    private static JFrame frame;
    private static JButton button;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(EventsOnPopupShowing::initUI);
            robot.delay(1000);
            clickOn(button);
            robot.delay(3000);
            if (gainedCount.get() != 1) {
                throw new RuntimeException("Unexpected 'focus gained' count: " + gainedCount);
            }
        } finally {
            SwingUtilities.invokeAndWait(EventsOnPopupShowing::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("EventsOnPopupShowing");
        button = new JButton("Start");
        button.addActionListener(e -> {
            JDialog d = new JDialog(frame, "Dialog", true);
            d.setBounds(300, 300, 200, 200);
            new Timer(1000, ee -> d.dispose()) {{ setRepeats(false); }}.start();
            d.setVisible(true);

            JWindow w = new JWindow(frame);
            w.add(new JTextField());
            w.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    gainedCount.incrementAndGet();
                }
            });
            w.setBounds(300, 300, 200, 200);
            w.setVisible(true);
            try {
                Thread.sleep(1000); // make sure all native events are processed
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        });
        frame.add(button);
        frame.setBounds(200, 200, 200, 200);
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

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
