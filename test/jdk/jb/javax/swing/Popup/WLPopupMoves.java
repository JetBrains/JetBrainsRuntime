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
 * @summary Verifies that the popup-style window can move under Wayland
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupMoves
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupMoves
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WLPopupMoves
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupMoves
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupMoves
 */
public class WLPopupMoves {

    private static JFrame frame;
    private static JWindow popup;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupMoves Test");
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

    public static void main(String... args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupMoves::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupMoves::initPopup);
        pause(robot);

        double uiScale = getUiScale();
        System.out.printf("UI scale: %.2f.\n", uiScale);
        int pixelThreshold = uiScale == 1.0 ? 0 : (int) Math.ceil(uiScale);
        System.out.printf("Pixel threshold for verifications: %d\n", pixelThreshold);

        int w = 120, h = 200;
        System.out.println("Set popup to (50, 50)");
        SwingUtilities.invokeAndWait(() -> {
            popup.setBounds(50, 50, w, h);
            popup.setVisible(true);
        });
        verifyBounds("Popup position after setting to (50, 50)\n", 50, 50, w, h, pixelThreshold);
        pause(robot);
        verifyBounds("Popup position (50, 50) after robot's pause\n", 50, 50, w, h, pixelThreshold);

        System.out.println("Set popup to (100, 100)");
        SwingUtilities.invokeAndWait(() -> {
            popup.setBounds(100, 100, w, h);
        });
        verifyBounds("Popup position after setting to (100, 100)\n", 100, 100, w, h, pixelThreshold);
        pause(robot);
        verifyBounds("Popup position (100, 100) after robot's pause\n", 100, 100, w, h, pixelThreshold);

        int x1 = (int) (toolkit.getScreenSize().width / (2 * uiScale));
        int y1 = (int) (toolkit.getScreenSize().height / (2 * uiScale));
        System.out.printf("Set popup to (%d, %d)\n", x1, y1);
        SwingUtilities.invokeAndWait(() -> {
            popup.setBounds(x1, y1, w, h);
        });
        verifyBounds(String.format("Popup position after setting to (%d, %d)\n", x1, y1), x1, y1, w, h, pixelThreshold);
        pause(robot);
        verifyBounds(String.format("Popup position (%d, %d) after robot's pause\n", x1, y1), x1, y1, w, h, pixelThreshold);
        
        int x2 = (int) (toolkit.getScreenSize().width / uiScale - 10 - w);
        int y2 = (int) (toolkit.getScreenSize().height / uiScale - 10 - h);
        System.out.printf("Set popup to (%d, %d). (to the bottom right corner) \n", x2, y2);
        SwingUtilities.invokeAndWait(() -> {
            popup.setBounds(x2, y2, w, h);
        });
        verifyBounds(String.format("Popup position after setting to (%d, %d)\n", x2, y2), x2, y2, w, h, pixelThreshold);
        pause(robot);
        verifyBounds(String.format("Popup position (%d, %d) after robot's pause\n", x2, y2), x2, y2, w, h, pixelThreshold);

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice device = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = device.getDefaultConfiguration();
        Insets insets = toolkit.getScreenInsets(gc);
        int x3 = (int) (toolkit.getScreenSize().width / uiScale - 10 - insets.right);
        int y3 = (int) (toolkit.getScreenSize().height / uiScale - 10 - insets.bottom);
        System.out.printf("Set popup to (%d, %d). (to the bottom right corner) \n", x3, y3);
        SwingUtilities.invokeAndWait(() -> {
            popup.setBounds(x2, y2, w, h);
        });
        int x3Relocated = x3 - w;
        int y3Relocated = y3 - h;
        verifyBounds(String.format("Popup position after setting to (%d, %d)\n", x3, y3), x3Relocated, y3Relocated, w, h, pixelThreshold);
        pause(robot);
        verifyBounds(String.format("Popup position (%d, %d) after robot's pause\n", x3, y3), x3Relocated, y3Relocated, w, h, pixelThreshold);

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

    private static void verifyBounds(String message, int x, int y, int w, int h, int pixelThreshold) {
        Rectangle bounds = popup.getBounds();
        System.out.printf("Check %s for bounds: %s\n", message, bounds);
        boolean isCorrectPosition = x - pixelThreshold <= bounds.x && bounds.x <= x + pixelThreshold &&
                y - pixelThreshold <= bounds.y && bounds.y <= y + pixelThreshold;
        if (!isCorrectPosition) {
            throw new RuntimeException(String.format("%s has wrong position. Expected: (%d, %d). Actual: (%d, %d)", message, x, y, bounds.x, bounds.y));
        }
        if (bounds.width != w || bounds.height != h) {
            throw new RuntimeException(String.format("%s has wrong size. Expected: (%d, %d). Actual: (%d, %d)", message, w, h, bounds.width, bounds.height));
        }
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }
}
