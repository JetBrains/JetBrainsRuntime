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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3353 Sibling popup window is shown below dialog on macOS
 * @key headful
 */

public class SiblingOrderTest {
    private static Robot robot;
    private static JFrame frame;
    private static final CompletableFuture<Boolean> popupButtonPressed = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(SiblingOrderTest::initUI);
            click(); // button in frame
            click(); // button in dialog
            click(); // button in popup
            popupButtonPressed.get(3, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(SiblingOrderTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("SiblingOrderTest");
        JButton b = new JButton("Dialog");
        b.addActionListener(e -> {
            JDialog d = new JDialog(frame, "Dialog");
            JButton b1 = new JButton("Popup");
            b1.addActionListener(e1 -> {
                JWindow w = new JWindow(frame);
                JButton b2 = new JButton("Finish");
                b2.addActionListener(e2 -> popupButtonPressed.complete(true));
                w.add(b2);
                w.setBounds(150, 150, 100, 100);
                w.setVisible(true);
            });
            d.add(b1);
            d.setBounds(100, 100, 200, 200);
            d.setVisible(true);
        });
        frame.add(b);
        frame.setBounds(50, 50, 300, 300);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void click() {
        robot.delay(1000);
        robot.mouseMove(200, 200);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
}