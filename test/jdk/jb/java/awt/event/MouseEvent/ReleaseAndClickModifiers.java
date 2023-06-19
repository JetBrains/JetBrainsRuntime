/*
 * Copyright 2023 JetBrains s.r.o.
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

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * @test
 * @summary Regression test for JBR-5720 Wrong modifiers are reported for mouse middle and right buttons' release/clicked events
 * @key headful
 */

public class ReleaseAndClickModifiers {
    private static final List<Integer> modifiers = Collections.synchronizedList(new ArrayList<>());

    private static Robot robot;
    private static JFrame frame;
    private static JPanel panel;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(ReleaseAndClickModifiers::initUI);
            robot.delay(1000);
            clickOn(panel, MouseEvent.BUTTON1);
            clickOn(panel, MouseEvent.BUTTON2);
            clickOn(panel, MouseEvent.BUTTON3);
            robot.delay(1000);
            if (!List.of(InputEvent.BUTTON1_DOWN_MASK, 0, 0,
                         InputEvent.BUTTON2_DOWN_MASK, 0, 0,
                         InputEvent.BUTTON3_DOWN_MASK, 0, 0)
                    .equals(modifiers)) {
                throw new RuntimeException("Wrong modifiers: " + modifiers);
            }
        } finally {
            SwingUtilities.invokeAndWait(ReleaseAndClickModifiers::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ReleaseAndClickModifiers");
        panel = new JPanel();
        panel.setPreferredSize(new Dimension(200, 200));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                logModifiers(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                logModifiers(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                logModifiers(e);
            }

            private void logModifiers(MouseEvent e) {
                modifiers.add(e.getModifiersEx());
            }
        });
        frame.add(panel);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void clickAt(int x, int y, int button) {
        robot.mouseMove(x, y);
        int buttonMask = InputEvent.getMaskForButton(button);
        robot.mousePress(buttonMask);
        robot.mouseRelease(buttonMask);
    }

    private static void clickOn(Component component, int button) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2, button);
    }
}
