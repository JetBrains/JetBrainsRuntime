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
import java.awt.Point;
import java.awt.Robot;

/**
 * @test
 * @summary Verifies that the popup-style window can change it's size and location
 * @requires os.family == "linux"
 * @key headful
 * @run main/othervm WLPopupChildLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupChildLocation
 */
public class WLPopupChildLocation {
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("WLPopupChildLocation Test");
            button1 = new JButton("Open popup");
            button1.addActionListener(e -> {
                Point pl = frame.getLocationOnScreen();
                pl.translate(100, 100);
                button2 = new JButton("Open child popup");
                JPanel panel2 = new JPanel();
                panel2.putClientProperty("wlawt.popup_position_unconstrained", Boolean.TRUE);
                panel2.add(button2);
                Popup popup = PopupFactory.getSharedInstance().getPopup(button1, panel2, pl.x, pl.y);
                button2.addActionListener(_ -> {
                    button3 = new JButton("Close");
                    JPanel panel3 = new JPanel();
                    panel3.putClientProperty("wlawt.popup_position_unconstrained", Boolean.TRUE);
                    panel3.add(button3);
                    Point pln = frame.getLocationOnScreen();
                    pln.translate(100, 100);
                    Popup popup2 = PopupFactory.getSharedInstance().getPopup(button2, panel3, pln.x, pln.y);
                    button3.addActionListener(_ -> {
                        popup2.hide();
                        popup.hide();
                    });
                    popup2.show();
                });
                popup.show();
            });
            frame.add(button1);
            frame.setSize(300, 300);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });

        Robot robot = new Robot();
        robot.waitForIdle();
        robot.delay(500);

        SwingUtilities.invokeAndWait(button1::doClick);
        robot.waitForIdle();
        robot.delay(500);

        SwingUtilities.invokeAndWait(button2::doClick);
        robot.waitForIdle();
        robot.delay(500);

        SwingUtilities.invokeAndWait(() -> {
            Point frameLoc = frame.getLocationOnScreen();
            Point button2Loc = button2.getLocationOnScreen();
            Point button3Loc = button3.getLocationOnScreen();
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
