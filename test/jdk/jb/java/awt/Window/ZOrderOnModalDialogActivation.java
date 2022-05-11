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
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-4463 Activating app-modal dialog brings all app windows to front
 * @key headful
 */

public class ZOrderOnModalDialogActivation {
    static final int EXPECTED_EXIT_CODE = 123;

    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static Process otherProcess;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeLater(ZOrderOnModalDialogActivation::initUI);
            robot.delay(1000);
            launchOtherProcess();
            robot.delay(1000);
            clickAt(350, 500); // Button 2 - make sure other process' window is activated
            robot.delay(1000);
            clickAt(200, 200); // modal dialog
            robot.delay(1000);
            clickAt(250, 500); // Button 1 (assuming other process' window is still shown above)
            if (!otherProcess.waitFor(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Child process hasn't exited");
            }
            if (otherProcess.exitValue() != EXPECTED_EXIT_CODE) {
                throw new RuntimeException("Unexpected exit code: " + otherProcess.exitValue());
            }
        } finally {
            SwingUtilities.invokeAndWait(ZOrderOnModalDialogActivation::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("ZOOMDA 1");
        frame1.setBounds(100, 100, 200, 200);
        frame1.setVisible(true);
        frame2 = new JFrame("ZOOMDA 2");
        frame2.setBounds(100, 400, 200, 200);
        frame2.setVisible(true);
        JDialog d = new JDialog(frame1, "ZOOMDA 3", true);
        d.setBounds(150, 150, 100, 100);
        d.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
        if (otherProcess != null) otherProcess.destroyForcibly();
    }

    private static void launchOtherProcess() throws Exception {
        otherProcess = Runtime.getRuntime().exec(new String[]{
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp",
                System.getProperty("java.class.path"),
                "ZOrderOnModalDialogActivationChild"
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
}

class ZOrderOnModalDialogActivationChild {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("ZOOMDAC");
            f.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    System.out.println();
                }
            });
            f.setLayout(new BorderLayout());
            JButton button1 = new JButton("Button 1");
            button1.addActionListener(e -> System.exit(ZOrderOnModalDialogActivation.EXPECTED_EXIT_CODE));
            f.add(button1, BorderLayout.WEST);
            f.add(new JButton("Button 2"), BorderLayout.EAST);
            f.setBounds(200, 450, 200, 100);
            f.setVisible(true);
        });
    }
}
