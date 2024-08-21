/*
 * Copyright 2024 JetBrains s.r.o.
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

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;

import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

/**
 * @test
 * @summary Verifies that it is possible to show a popup whose parent
 *          itself is a popup
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main WLPopupAsParent
 */
public class WLPopupAsParent {
    private static JFrame frame;
    private static JWindow popup1;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupAsParent Test");
        frame.setSize(1000, 1000); // must be large enough for popups to fit
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void showPopup1() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        popup1 = new JWindow(frame);
        popup1.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup1, frame);
        popup1.setBounds(15, 33, 100, 50);
        popup1.add(popupContents);
        popup1.setVisible(true);
    }

    private static void showPopup2() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("second-level popup"));
        JWindow popup2 = new JWindow(popup1);
        popup2.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup2, popup1);
        popup2.setBounds(85, 267, 200, 100);
        popup2.add(popupContents);
        popup2.setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupAsParent::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupAsParent::showPopup1);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupAsParent::showPopup2);
        pause(robot);

        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }
}
