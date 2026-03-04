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

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import java.awt.*;

/**
 * @test
 * @summary Verifies that the popup are located at screen coordinates regardless owner
 * @requires os.family == "linux"
 * @key headful
 * @run main/othervm WLPopupOwnerLocation
 */
public class WLPopupOwnerLocation {
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;

    private static JButton popupButton1;
    private static JButton popupButton2;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("WLPopupOwnerLocation Test");
            button1 = new JButton("Open popup (owner=frame)");
            button1.addActionListener(e -> {
                Point pl = frame.getLocationOnScreen();
                popupButton1 = new JButton("Close popup");
                Popup popup = PopupFactory.getSharedInstance().getPopup(frame, popupButton1, pl.x + 100, pl.y + 100);
                popupButton1.addActionListener(_ -> popup.hide());
                popup.show();
            });
            frame.add(button1, BorderLayout.NORTH);

            button2 = new JButton("Open popup (owner=button)");
            button2.addActionListener(e -> {
                Point pl = frame.getLocationOnScreen();
                popupButton2 = new JButton("Close popup");
                Popup popup = PopupFactory.getSharedInstance().getPopup(button1, popupButton2, pl.x + 100, pl.y + 100);
                popupButton2.addActionListener(_ -> popup.hide());
                popup.show();
            });
            frame.add(button2, BorderLayout.SOUTH);
            frame.setSize(300, 200);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });

        Robot robot = new Robot();
        robot.waitForIdle();
        robot.delay(500);

        SwingUtilities.invokeAndWait(button1::doClick);
        robot.waitForIdle();

        SwingUtilities.invokeAndWait(button2::doClick);
        robot.waitForIdle();
        robot.delay(500);

        SwingUtilities.invokeAndWait(() -> {
            Point frameLoc = frame.getLocationOnScreen();
            Point button2Loc = popupButton1.getLocationOnScreen();
            Point button3Loc = popupButton2.getLocationOnScreen();
            System.out.printf("Frame location: %s, button1 location: %s, button2 location: %s%n", frameLoc, button2Loc, button3Loc);
            if (button2Loc.distance(button3Loc) > getTolerance()) {
                throw new RuntimeException(String.format(
                        "Buttons are not located in the same spot: %s vs %s", button2Loc, button3Loc));
            }
            frame.dispose();
        });
    }

    private static int getTolerance() {
        String uiScaleString = System.getProperty("sun.java2d.uiScale");
        int tolerance = uiScaleString == null ? 0 : (int) Math.ceil(Double.parseDouble(uiScaleString));
        System.out.printf("Scale settings: debug scale: %s, tolerance level: %d\n", uiScaleString, tolerance);
        return tolerance;
    }
}
