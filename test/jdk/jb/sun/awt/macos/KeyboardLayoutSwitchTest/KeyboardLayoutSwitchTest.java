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
import java.awt.event.KeyEvent;

public class KeyboardLayoutSwitchTest implements Runnable {

    private static Robot robot;
    private JFrame frame;

    public static void main(String[] args) throws Exception {

        System.out.println("VK_TAB = " + KeyEvent.VK_TAB);
        robot = new Robot();
        robot.setAutoDelay(50);

        KeyboardLayoutSwitchTest test = new KeyboardLayoutSwitchTest();
        SwingUtilities.invokeAndWait(test);
        robot.delay(3000);

        try {
            SwingUtilities.invokeAndWait(() ->
                    pressCtrlKey(KeyEvent.VK_TAB));
        } finally {
            SwingUtilities.invokeAndWait(() -> test.frame.dispose());
            robot.delay(2000);
        }
        System.out.println("Test PASSED");
    }

    private static void pressCtrlKey(int vk) {
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(vk);
        robot.keyRelease(vk);
        robot.keyRelease(KeyEvent.VK_CONTROL);
    }

    @Override
    public void run() {
        frame = new JFrame(getClass().getSimpleName());
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
    }
}