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
 * @summary Verifies that the popup-style window can change its visibility
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupVisibility
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupVisibility
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupVisibility
 */
public class WLPopupVisibility {

    private static JFrame frame;
    private static JWindow popup;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupVisibility Test");
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

        SwingUtilities.invokeAndWait(WLPopupVisibility::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupVisibility::initPopup);
        pause(robot);

        System.out.println("Action: set the popup visible");
        SwingUtilities.invokeAndWait(() -> popup.setVisible(true));
        boolean isVisible1 = popup.isVisible();
        pause(robot);
        boolean isVisible2 = popup.isVisible();

        if (!isVisible1 || !isVisible2) {
            throw new RuntimeException("Expected result: popup is visible");
        }

        System.out.println("Action: set the popup disabled");
        SwingUtilities.invokeAndWait(() -> popup.setEnabled(false));
        boolean isEnabled3 = popup.isEnabled();
        boolean isVisible3 = popup.isVisible();
        pause(robot);
        boolean isEnabled4 = popup.isEnabled();
        boolean isVisible4 = popup.isVisible();
        if (isEnabled3 || isEnabled4) {
            throw new RuntimeException("Expected result: popup is disabled");
        }
        if (!isVisible3 || !isVisible4) {
            throw new RuntimeException("Expected result: disabled popup remains visible");
        }

        System.out.println("Action: set the popup invisible");
        SwingUtilities.invokeAndWait(() -> popup.setVisible(false));
        boolean isVisible5 = popup.isVisible();
        pause(robot);
        boolean isVisible6 = popup.isVisible();
        if (isVisible5 && isVisible6) {
            throw new RuntimeException("Expected result: disabled popup remains visible");
        }

        System.out.println("Action: set popup enabled and visible");
        SwingUtilities.invokeAndWait(() -> {
            popup.setVisible(true);
            popup.setEnabled(true);
        });
        boolean isEnabled7 = popup.isEnabled();
        boolean isVisible7 = popup.isVisible();
        pause(robot);
        boolean isEnabled8 = popup.isEnabled();
        boolean isVisible8 = popup.isVisible();
        if (!isEnabled7 || !isEnabled8) {
            throw new RuntimeException("Expected result: popup is enabled");
        }
        if (!isVisible7 || !isVisible8) {
            throw new RuntimeException("Expected result: popup becoming visible");
        }

        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }

}
