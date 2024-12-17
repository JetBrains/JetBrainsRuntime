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
 * @summary Verifies that the popup-style window can change it's size and location
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupLocation
 */
public class WLPopupLocation {

    private static JFrame frame;
    private static JWindow popup;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupLocation Test");
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void initPopup() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, frame);
        popup.add(popupContents);
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupLocation::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupLocation::initPopup);
        pause(robot);

        try {
            int w1 = 150, h1 = 200;
            int x1 = 100, y1 = 100;
            System.out.printf("Action: locate to (%d, %d), set size (%d, %d)\n", x1, y1, w1, h1);
            SwingUtilities.invokeAndWait(() -> {
                popup.setVisible(true);
                popup.setSize(2, 5);
                popup.setSize(89, 17);
                popup.setSize(11, 3);
                popup.setSize(w1, h1);
                popup.setLocation(x1, y1);
            });
            int toleranceLevel = getTolerance();

            System.out.printf("Real bounds: %s\n", popup.getBounds());
            if (isOutsideTolerance(w1, h1, popup.getSize().width, popup.getSize().height, toleranceLevel)) {
                throw new RuntimeException(String.format("Incorrect size (%d, %d), expected (%d, %d)", popup.getSize().width, popup.getSize().height, w1, h1));
            }
            if (isOutsideTolerance(x1, y1, popup.getBounds().x, popup.getBounds().y, toleranceLevel)) {
                throw new RuntimeException(String.format("Wrong location (via getBounds()): (%d, %d). Expected: (%d, %d)", popup.getBounds().x, popup.getBounds().y, x1, y1));
            }
            pause(robot);
            System.out.printf("Real bounds after a pause: %s\n", popup.getBounds());
            if (isOutsideTolerance(w1, h1, popup.getSize().width, popup.getSize().height, toleranceLevel)) {
                throw new RuntimeException(String.format("Incorrect size (%d, %d) after robot's wait for idle, expected (%d, %d)", popup.getSize().width, popup.getSize().height, w1, h1));
            }
            if (isOutsideTolerance(x1, y1, popup.getBounds().x, popup.getBounds().y, toleranceLevel)) {
                throw new RuntimeException(String.format("Wrong location (via getBounds()) after robot's wait for idle: (%d, %d). Expected: (%d, %d)", popup.getBounds().x, popup.getBounds().y, x1, y1));
            }

            int x2 = 200, y2 = 200;
            System.out.printf("Action: set popup location to (%d, %d)\n", x2, y2);
            SwingUtilities.invokeAndWait(() -> {
                popup.setLocation(x2, y2);
            });
            System.out.printf("Real bounds: %s\n", popup.getBounds());
            if (isOutsideTolerance(w1, h1, popup.getSize().width, popup.getSize().height, toleranceLevel)) {
                throw new RuntimeException(String.format("Incorrect size (%d, %d), expected (%d, %d)", popup.getSize().width, popup.getSize().height, w1, h1));
            }
            if (isOutsideTolerance(x2, y2, popup.getBounds().x, popup.getBounds().y, toleranceLevel)) {
                throw new RuntimeException(String.format("Wrong location (via getBounds()): (%d, %d). Expected: (%x, %d)", popup.getBounds().x, popup.getBounds().y, x2, y2));
            }
            pause(robot);
            System.out.printf("Real bounds after a pause: %s\n", popup.getBounds());
            if (isOutsideTolerance(w1, h1, popup.getSize().width, popup.getSize().height, toleranceLevel)) {
                throw new RuntimeException(String.format("Incorrect size (%d, %d) after robot's wait for idle, expected (%d, %d)", popup.getSize().width, popup.getSize().height, w1, h1));
            }
            if (isOutsideTolerance(x2, y2, popup.getBounds().x, popup.getBounds().y, toleranceLevel)) {
                throw new RuntimeException(String.format("Wrong location (via getBounds()) after robot's wait for idle: (%d, %d). Expected: (%d, %d)", popup.getBounds().x, popup.getBounds().y, x2, y2));
            }
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
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
}
