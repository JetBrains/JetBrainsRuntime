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
import java.awt.event.KeyEvent;

/**
 * @test
 * @summary Regression test for JBR-4281 Window losing focus isn't detected in some cases on macOS
 * @key headful
 * @requires (os.family == "mac")
 */

public class MacSpecialFocusLostCase {
    private static Robot robot;
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(MacSpecialFocusLostCase::initUI);
            checkFocusedStatus(true);
            pressCmdSpace(); // open Spotlight popup
            checkFocusedStatus(false);
            pressEsc(); // close Spotlight popup
            checkFocusedStatus(true);
        } finally {
            pressEsc(); // make sure popup is closed in any case
            SwingUtilities.invokeAndWait(MacSpecialFocusLostCase::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("MacSpecialFocusLostCase");
        frame.setBounds(200, 200, 300, 200);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void checkFocusedStatus(boolean expected) throws Exception {
        robot.delay(1000);
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> result[0] = frame.isFocused());
        if (result[0] != expected) {
            throw new RuntimeException(expected ? "Frame isn't focused" : "Frame is still focused");
        }
    }

    private static void pressCmdSpace() {
        robot.keyPress(KeyEvent.VK_META);
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_META);
    }

    private static void pressEsc() {
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
    }
}
