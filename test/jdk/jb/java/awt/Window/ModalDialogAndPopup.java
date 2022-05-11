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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;

/**
 * @test
 * @summary Regression test for JBR-4957 Modal dialog is hidden by sibling popup on Linux
 * @key headful
 */

public class ModalDialogAndPopup {
    private static final CompletableFuture<Boolean> modalDialogButtonClicked = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeLater(ModalDialogAndPopup::initUI);
            robot.delay(2000);
            clickAt(350, 350);
            robot.delay(1000);
            clickAt(450, 350);
            modalDialogButtonClicked.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(ModalDialogAndPopup::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ModalDialogAndPopup");
        frame.setBounds(200, 200, 500, 300);
        frame.setVisible(true);
        JWindow w = new JWindow(frame);
        w.setBounds(300, 250, 200, 200);
        w.setVisible(true);
        JDialog d = new JDialog(frame, true);
        JButton b = new JButton("button");
        b.addActionListener(e -> modalDialogButtonClicked.complete(true));
        d.add(b);
        d.setBounds(400, 250, 200, 200);
        d.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
}
