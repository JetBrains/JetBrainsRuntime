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
import java.awt.*;
import java.awt.geom.AffineTransform;

import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

/**
 * @test
 * @summary Verifies that the size of a popup-style window can be changed
 *          under Wayland
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupResize
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupResize
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WLPopupResize
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupResize
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupResize
 */
public class WLPopupResize {
    private static JFrame frame;
    private static JWindow popup;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupResize Test");
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void showPopup() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, frame);
        popup.setSize(100, 50);
        popup.add(popupContents);
        popup.setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupResize::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupResize::showPopup);
        pause(robot);

        try {
            int tolerance = getTolerance();

            int x = 10, y = 20, w = 120, h = 80;
            System.out.println("Set popup size to (120, 80)");
            SwingUtilities.invokeAndWait(() -> {
                popup.setBounds(x, y, w, h);
            });
            Rectangle bounds = popup.getBounds();
            if (isOutsideTolerance(x, y, bounds.x, bounds.y, tolerance)) {
                throw new RuntimeException("Popup position has unexpectedly changed. Bounds: " + popup.getBounds());
            }
            if (isOutsideTolerance(w, h, bounds.width, bounds.height, tolerance)) {
                throw new RuntimeException("Popup size wasn't correctly changed. Bounds: " + popup.getBounds());
            }
            pause(robot);
            System.out.println("Next checks after robot's waiting for idle.");

            bounds = popup.getBounds();
            if (isOutsideTolerance(x, y, bounds.x, bounds.y, tolerance)) {
                throw new RuntimeException("Popup position has unexpectedly changed. Bounds: " + popup.getBounds());
            }
            if (isOutsideTolerance(w, h, bounds.width, bounds.height, tolerance)) {
                throw new RuntimeException("Popup size wasn't correctly changed. Bounds: " + popup.getBounds());
            }
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static int getTolerance() {
        String uiScaleString = System.getProperty("sun.java2d.uiScale");
        int tolerance = uiScaleString == null ? 0 : (int) Math.ceil(Double.parseDouble(uiScaleString));
        System.out.printf("Scale settings: debug scale: %s, tolerance level: %d\n", uiScaleString, tolerance);
        return tolerance;
    }

    private static boolean isOutsideTolerance(int expectedX, int expectedY, int realX, int realY, int tolerance) {
        return Math.abs(realX - expectedX) > tolerance || Math.abs(realY - expectedY) > tolerance;
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }
}