/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2843 No caret in merge window
 * @key headful
 */

public class SequentialModalDialogsTest {
    private static final CompletableFuture<Boolean> secondDialogShown = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> typedInDialog = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JButton frameButton;
    private static JTextField windowField;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(SequentialModalDialogsTest::initUI);
            robot.delay(1000);
            clickOn(frameButton);
            secondDialogShown.get(10, TimeUnit.SECONDS);
            pressAndRelease(KeyEvent.VK_ENTER);
            typedInDialog.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(SequentialModalDialogsTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("SequentialModalDialogsTest");
        frameButton = new JButton("Open dialogs");
        frameButton.addActionListener(e -> {
            showDialogs();
        });
        frame.add(frameButton);
        frame.pack();
        frame.setVisible(true);
    }

    private static void showDialogs() {
        JDialog d1 = new JDialog(frame, true);
        JTextField t1 = new JTextField("dialog 1");
        t1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // using invokeLater to emulate some moment after dialog showing
                SwingUtilities.invokeLater(() -> d1.setVisible(false));
            }
        });
        d1.add(t1);
        d1.pack();
        d1.setVisible(true);
        JDialog d2 = new JDialog(frame, true);
        JTextField t2 = new JTextField("dialog 2");
        t2.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // using invokeLater to emulate some moment after dialog showing
                SwingUtilities.invokeLater(() -> secondDialogShown.complete(true));
            }
        });
        t2.addActionListener(e -> typedInDialog.complete(true));
        d2.add(t2);
        d2.pack();
        d2.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y) {
        robot.delay(1000); // needed for GNOME, to give it some time to update internal state after window showing
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
