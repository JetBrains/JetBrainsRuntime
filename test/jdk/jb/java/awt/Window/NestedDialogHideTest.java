/*
 * Copyright 2021-2023 JetBrains s.r.o.
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
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3072 Deadlock on nested dialog hiding
 * @key headful
 */

public class NestedDialogHideTest {
    private static final CompletableFuture<Boolean> dialogHidden = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(NestedDialogHideTest::initUI);
            robot.delay(2000);
            clickAt(button1);
            robot.delay(2000);
            clickAt(button2);
            robot.delay(2000);
            clickAt(button3);
            dialogHidden.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(NestedDialogHideTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("NestedDialogHideTest");
        button1 = new JButton("Open dialog");
        button1.addActionListener(e -> {
            JDialog d = new JDialog(frame, "level 1");
            button2 = new JButton("Open nested dialog");
            button2.addActionListener(ee -> {
                JDialog d2 = new JDialog(d, "level 2");
                button3 = new JButton("Hide");
                button3.addActionListener(eee -> d.setVisible(false));
                d2.add(button3);
                d2.setBounds(240, 240, 200, 200);
                d2.setVisible(true);
            });
            d.add(button2);
            d.setBounds(220, 220, 200, 200);
            d.setVisible(true);
            d.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentHidden(ComponentEvent e) {
                    dialogHidden.complete(true);
                }
            });
        });
        frame.add(button1);
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

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
