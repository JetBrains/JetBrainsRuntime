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
import java.awt.geom.AffineTransform;

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

        System.out.println("Action: locate to (15, 20), set size (150, 200)");
        SwingUtilities.invokeAndWait(() -> {
            popup.setVisible(true);
            popup.setSize(150, 200);
            popup.setLocation(100, 100);
        });
        if (popup.getSize().width != 150 || popup.getSize().height != 200) {
            throw new RuntimeException(String.format("Incorrect size (%d, %d), expected (150, 200)", popup.getSize().width, popup.getSize().height));
        }
        if (popup.getBounds().x != 100 || popup.getBounds().y != 100) {
            throw new RuntimeException(String.format("Wrong location (via getBounds()): (%d, %d). Expected: (100, 100)", popup.getBounds().x, popup.getBounds().y));
        }
        pause(robot);
        if (popup.getSize().width != 150 || popup.getSize().height != 200) {
            throw new RuntimeException(String.format("Incorrect size (%d, %d) after robot's wait for idle, expected (150, 200)", popup.getSize().width, popup.getSize().height));
        }
        if (popup.getBounds().x != 100 || popup.getBounds().y != 100) {
            throw new RuntimeException(String.format("Wrong location (via getBounds()) after robot's wait for idle: (%d, %d). Expected: (100, 100)", popup.getBounds().x, popup.getBounds().y));
        }

        System.out.println("Action: set popup size to (100, 200)");
        SwingUtilities.invokeAndWait(() -> {
            popup.setLocation(200, 200);
        });
        if (popup.getSize().width != 150 || popup.getSize().height != 200) {
            throw new RuntimeException(String.format("Incorrect size (%d, %d), expected (150, 200)", popup.getSize().width, popup.getSize().height));
        }
        if (popup.getBounds().x != 200 || popup.getBounds().y != 200) {
            throw new RuntimeException(String.format("Wrong location (via getBounds()): (%d, %d). Expected: (100, 100)", popup.getBounds().x, popup.getBounds().y));
        }
        pause(robot);
        if (popup.getSize().width != 150 || popup.getSize().height != 200) {
            throw new RuntimeException(String.format("Incorrect size (%d, %d) after robot's wait for idle, expected (150, 200)", popup.getSize().width, popup.getSize().height));
        }
        if (popup.getBounds().x != 200 || popup.getBounds().y != 200) {
            throw new RuntimeException(String.format("Wrong location (via getBounds()) after robot's wait for idle: (%d, %d). Expected: (100, 100)", popup.getBounds().x, popup.getBounds().y));
        }
        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static Double getUiScale() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice device = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = device.getDefaultConfiguration();
        AffineTransform transform = gc.getDefaultTransform();
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();
        if (scaleX != scaleY) {
            System.out.println("Skip test due to non-uniform display scale");
            System.exit(0);
        }
        return scaleX;
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }

}
