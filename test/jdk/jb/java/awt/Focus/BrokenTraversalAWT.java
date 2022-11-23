/*
 * Copyright 2022 JetBrains s.r.o.
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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * @test
 * @summary Regression test for JBR-5045 Invisible component can break focus cycle
 * @key headful
 */

public class BrokenTraversalAWT {
    private static Robot robot;
    private static Frame frame;
    private static Button button1;
    private static Button button2;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            EventQueue.invokeAndWait(BrokenTraversalAWT::initUI);
            robot.delay(1000);
            clickOn(button1);
            robot.delay(500);
            checkFocus(button1);
            pressAndReleaseTab();
            robot.delay(500);
            checkFocus(button2);
            pressAndReleaseTab();
            robot.delay(500);
            checkFocus(button1);
        }
        finally {
            EventQueue.invokeAndWait(BrokenTraversalAWT::disposeUI);
        }
    }

    private static void initUI() {
        frame = new Frame("BrokenTraversalAWT");
        frame.setLayout(new FlowLayout());

        button1 = new Button("1");
        frame.add(button1);
        button2 = new Button("2");
        frame.add(button2);

        Panel p = new Panel();
        p.setVisible(false);
        p.setFocusTraversalPolicyProvider(true);
        p.setFocusTraversalPolicy(new DefaultFocusTraversalPolicy() {
            @Override
            public Component getDefaultComponent(Container aContainer) {
                return p;
            }
        });
        frame.add(p);

        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void checkFocus(Component component) throws Exception {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner != component) {
            throw new IllegalStateException("Focus owner: " + focusOwner + ", expected: " + component);
        }
    }

    private static void pressAndReleaseTab() {
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
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