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

import javax.swing.*;
import java.awt.*;

import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

/**
 * @test
 * @summary Verifies that it is possible to show a popup whose parent
 *          itself is a popup
 * @requires os.family == "linux"
 * @key headful
 * @run main WLPopupAsParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupAsParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WLPopupAsParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupAsParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupAsParent
 */
public class WLPopupAsParent {
    private static JFrame frame;
    private static Popup popup1;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupAsParent Test");
        frame.setSize(1000, 1000); // must be large enough for popups to fit
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void showPopup1() {
        Point p = frame.getLocationOnScreen();
        popup1 = PopupFactory.getSharedInstance().getPopup(
                frame,
                new JLabel("test popup"),
                p.x + 15,
                p.y + 33);
        popup1.show();
    }

    private static void showPopup2() {
        Point p = frame.getLocationOnScreen();
        Popup popup2 = PopupFactory.getSharedInstance().getPopup(
                frame,
                new JLabel("second-level popup"),
                p.x + 85,
                p.y + 267);
        popup2.show();
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
