/*
 * Copyright 2025 JetBrains s.r.o.
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
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;

/**
 * @test
 * @summary Verifies that the popup-style window is shown even if located outside of parent's bounds
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupOutsideParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=0.8 WLPopupOutsideParent
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.3 WLPopupOutsideParent
 */
public class WLPopupOutsideParent {
    private static JFrame frame;
    private static JWindow popup;

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupOutsideParent::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupOutsideParent::initPopup);
        pause(robot);

        var parentSize = frame.getSize();
        var popupSize = popup.getSize();
        try {
            // These popups should appear at uncorrected coordinates
            System.out.println("Verifying that popups can appear at \"safe\" coordinates");
            testPopupAt(robot, -10, -10);
            testPopupAt(robot, parentSize.width - 1, parentSize.height - 1);

            // These coordinates will need correction by the toolkit as they place
            // the popup outside its parent's bounds.
            System.out.println("Verifying that popups can appear even if located outside parent's bounds");
            testPopupAt(robot, parentSize.width + 10, parentSize.height + 10);
            testPopupAt(robot, parentSize.width, 1);
            testPopupAt(robot, 1, parentSize.height);
            testPopupAt(robot, -popupSize.width - 1, 0);
            testPopupAt(robot, -popupSize.width - 1, -popupSize.height - 1);

            System.out.println("Verifying that popups can be moved to locations outside parent's bounds");
            try {
                showPopupAt(0, 0);

                testMovePopupTo(robot, parentSize.width + 10, parentSize.height + 10);
                testMovePopupTo(robot, parentSize.width, 1);
                testMovePopupTo(robot, 1, parentSize.height);
                testMovePopupTo(robot, -popupSize.width - 1, 0);
                testMovePopupTo(robot, -popupSize.width - 1, -popupSize.height - 1);
            } finally {
                SwingUtilities.invokeAndWait(() -> { popup.setVisible(false); });
            }
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupOutsideParent Test");
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void initPopup() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        popup = new JWindow(frame);
        popupContents.setBackground(Color.WHITE);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, frame);
        popup.add(popupContents);
    }

    private static void showPopupAt(int x, int y) throws Exception {
        int w = 100, h = 100;
        System.out.printf("Action: show popup at (%d, %d), set the size to (%d, %d)\n", x, y, w, h);
        SwingUtilities.invokeAndWait(() -> {
            popup.setSize(w, h);
            popup.setLocation(x, y);
            popup.setVisible(true);
        });
    }

    private static void testPopupAt(Robot robot, int x, int y) throws Exception {
        try {
            showPopupAt(x, y);

            // Pause to let the popup_done event get delivered if it was actually sent by the server.
            pause(robot);
            System.out.printf("The real location after a pause: %s\n", popup.getLocation());
            // NB: we don't check the location, just the fact that the popup is visible because the real
            //     location might be affected by the server in a completely unpredictable way.
            if (!popup.isVisible()) {
                throw new RuntimeException("Popup is not visible after a pause");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> { popup.setVisible(false); });
        }
    }

    private static void testMovePopupTo(Robot robot, int x, int y) throws Exception {
        System.out.printf("Action: move popup to (%d, %d)\n", x, y);
        SwingUtilities.invokeAndWait(() -> {
            popup.setLocation(x, y);
        });
        // Pause to let the popup_done event get delivered if it was actually sent by the server.
        pause(robot);
        System.out.printf("The real location after a pause: %s\n", popup.getLocation());
        // NB: we don't check the location, just the fact that the popup is visible because the real
        //     location might be affected by the server in a completely unpredictable way.
        if (!popup.isVisible()) {
            throw new RuntimeException("Popup is not visible after a pause");
        }
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }
}
