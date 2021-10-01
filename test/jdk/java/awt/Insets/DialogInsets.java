/*
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

/**
 * @test
 * @key headful
 * @summary Verifies that JDialog's insets are the same every time the dialog is created.
 * @requires (os.family == "linux")
 * @run main DialogInsets
 */

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.lang.reflect.InvocationTargetException;

public class DialogInsets {
    private static Robot robot;
    private static Insets insets;

    public static void main(String[] args) throws Exception {
        robot = new Robot();

        final JFrame frame = new JFrame("Test Dialog Demo");
        frame.setBounds(100, 100, 400, 300);
        frame.setVisible(true);

        robot.delay(300);

        for (int i = 0; i < 16; i++) {
            runSwing(() -> showDialog(frame));
            robot.delay(300);
        }

        runSwing( () -> frame.dispose() );
    }

    private static void showDialog(Frame parent) {
        final JLabel content = new JLabel("test label", SwingConstants.CENTER);
        final JDialog dialog = new JDialog(parent, "Test Dialog", true);

        if (insets == null) {
            // Slow down the first time in order to increase the chance to get the right insets.
           robot.delay(300);
        }

        dialog.setContentPane(content);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.pack();

        final Insets localInsets = dialog.getInsets();
        System.out.println(localInsets);

        if (insets == null) {
            insets = localInsets;
        } else if (!insets.equals(localInsets)) {
            throw new RuntimeException("insets differ (" + insets + " before, " + localInsets + " now)");
        }

        final Timer closeTimer = new Timer(300, (ActionEvent event) -> { dialog.setVisible(false); dialog.dispose(); } );
        closeTimer.setRepeats(false);
        closeTimer.start();

        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void runSwing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException ignored) {
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
