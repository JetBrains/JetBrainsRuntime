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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * @test
 * @summary Regression test for JBR-5458 Exception at dialog closing
 * @key headful
 */

public class UndecoratedDialogInTransientsChain {
    private static Robot robot;
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeLater(UndecoratedDialogInTransientsChain::initUI);
            robot.delay(1000);
            clickAtCenter();
            robot.delay(2000);
            clickAtCenter();
            robot.delay(1000);
            if (frame.isDisplayable()) {
                throw new IllegalStateException("Frame should have been closed");
            }
        } finally {
            SwingUtilities.invokeAndWait(UndecoratedDialogInTransientsChain::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("UndecoratedDialogInTransientsChain");
        JButton b1 = new JButton("Close");
        b1.addActionListener(e -> frame.dispose());
        frame.add(b1);
        frame.setBounds(200, 200, 400, 400);
        frame.setVisible(true);
        JDialog d = new JDialog(frame, "Dialog", true);
        JButton b2 = new JButton("Close");
        b2.addActionListener(e -> {
            JDialog d2 = new JDialog(frame);
            d2.setUndecorated(true);
            d2.setVisible(true);
            robot.delay(1000); // wait for the window to be realized natively
            d2.dispose();
            d.dispose();
        });
        d.add(b2);
        d.setBounds(300, 300, 200, 200);
        d.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void clickAtCenter() {
        robot.mouseMove(400, 400);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
}
