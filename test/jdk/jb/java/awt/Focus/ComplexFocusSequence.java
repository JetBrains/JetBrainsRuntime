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
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import javax.swing.*;

/**
 * @test
 * @summary Regression test for JBR-5684 Focus state is broken after closing of modal dialog in an inactive application
 * @key headful
 */

public class ComplexFocusSequence {
    private static final CompletableFuture<Boolean> success = new CompletableFuture<>();

    private static Robot robot;
    private static JFrame frame;
    private static JButton dialogButton;
    private static Process otherProcess;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(ComplexFocusSequence::initUI);
            launchOtherProcess();
            robot.delay(1000);
            clickAt(200, 500); // make sure other process'es window is activated
            robot.delay(1000);
            SwingUtilities.invokeLater(ComplexFocusSequence::showInactiveDialog);
            robot.delay(1000);
            clickOn(dialogButton);
            robot.delay(1000);
            typeEnter();
            success.get(3, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(ComplexFocusSequence::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ComplexFocusSequence");
        JTextField textField = new JTextField(30);
        textField.addActionListener(e -> success.complete(true));
        frame.add(textField);
        frame.pack();
        frame.setLocation(100, 100);
        frame.setVisible(true);
    }

    private static void showInactiveDialog() {
        JComboBox<?> cb = new JComboBox<>();
        cb.setEditable(true);
        JDialog d = new JDialog(frame, true);
        d.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                cb.requestFocus();
            }
        });
        d.add(cb, BorderLayout.CENTER);
        dialogButton = new JButton("Close");
        dialogButton.addActionListener(ee -> {
            d.dispose();
            spinEventLoop();
        });
        d.add(dialogButton, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    private static void spinEventLoop() {
        EventQueue eq = Toolkit.getDefaultToolkit().getSystemEventQueue();
        SecondaryLoop loop = eq.createSecondaryLoop();
        new Thread(() -> {
            do {
                LockSupport.parkNanos(100_000_000);
            } while (!loop.exit());
        }).start();
        loop.enter();
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
        if (otherProcess != null) otherProcess.destroyForcibly();
    }

    private static void launchOtherProcess() throws Exception {
        otherProcess = Runtime.getRuntime().exec(new String[]{
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp",
                System.getProperty("java.class.path"),
                "ComplexFocusSequenceChild"
        });
        if (otherProcess.getInputStream().read() == -1) {
            throw new RuntimeException("Error starting process");
        }
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

    private static void typeEnter() {
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
    }
}

class ComplexFocusSequenceChild {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("ComplexFocusSequence 2");
            f.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    System.out.println();
                }
            });
            f.setBounds(100, 400, 200, 200);
            f.setVisible(true);
        });
    }
}
