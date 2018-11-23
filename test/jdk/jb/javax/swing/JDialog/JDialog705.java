/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/* @test
 * @summary regression test on JRE-705 Z-order of child windows is broken on Mac OS
 * @run main/othervm JDialog705
 */

public class JDialog705 {

    private static Robot robot;
    static {
        try {
            robot = new Robot();
        } catch (AWTException e1) {
            e1.printStackTrace();
        }
    }

    private static JFrame jFrame;
    private static Window windowAncestor;
    private static JDialog modalBlocker;

    public static void main(String[] args) throws Exception {

        SwingUtilities.invokeAndWait(() -> {
            jFrame = new JFrame("Wrong popup z-order");
            jFrame.setSize(200, 200);

            JPanel jPanel = new JPanel();
            jPanel.setPreferredSize(new Dimension(200, 200));
            jPanel.setBackground(Color.BLACK);

            Popup popup = PopupFactory.getSharedInstance().getPopup(jFrame, jPanel, 100, 100);
            windowAncestor = SwingUtilities.getWindowAncestor(jPanel);
            ((RootPaneContainer) windowAncestor).getRootPane().putClientProperty("SIMPLE_WINDOW", true);
            windowAncestor.setFocusable(true);
            windowAncestor.setFocusableWindowState(true);
            windowAncestor.setAutoRequestFocus(true);

            jFrame.setVisible(true);
            popup.show();


            modalBlocker = new JDialog(windowAncestor, "Modal Blocker");
            modalBlocker.setModal(true);
            modalBlocker.setSize(new Dimension(200, 200));
            modalBlocker.setLocation(200, 200);
            modalBlocker.addWindowListener(new DialogListener());

            modalBlocker.setVisible(true);
        });
    }

    private static boolean checkImage(Container window, Dimension shotSize, int x, int y, int maxWidth, int maxHeight) {

        boolean result = true;

        System.out.println("checking for expectedX: " + x + "; expectedY: " + y);
        System.out.println("              maxWidth: " + maxWidth + ";  maxHeight: " + maxHeight);

        Rectangle captureRect = new Rectangle(window.getLocationOnScreen(), shotSize);
        BufferedImage screenImage = robot.createScreenCapture(captureRect);

        int rgb;
        int expectedRGB = screenImage.getRGB(x, y) & 0x00FFFFFF;

        System.out.println("  expected rgb value: " + Integer.toHexString(expectedRGB));
        for (int col = 1; col < maxWidth; col++) {
            for (int row = 1; row < maxHeight; row++) {
                // remove transparance
                rgb = screenImage.getRGB(col, row) & 0x00FFFFFF;

                result = (expectedRGB == rgb);
                if (expectedRGB != rgb) {
                    System.out.println("at row: " + row + "; col: " + col);
                    System.out.println("  expected rgb value: " + Integer.toHexString(expectedRGB));
                    System.out.println("    actual rgb value: " + Integer.toHexString(rgb));
                    break;
                }
            }
            if (!result) break;
        }

        try {
            ImageIO.write(screenImage, "bmp", new File("test705" + window.getName() + ".bmp"));
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        return result;
    }

    static class DialogListener implements WindowListener {

        @Override
        public void windowClosing(WindowEvent e) {

        }

        @Override
        public void windowClosed(WindowEvent e) {

        }

        @Override
        public void windowIconified(WindowEvent e) {

        }

        @Override
        public void windowDeiconified(WindowEvent e) {

        }

        @Override
        public void windowActivated(WindowEvent e) {

        }

        @Override
        public void windowDeactivated(WindowEvent e) {

        }

        @Override
        public void windowOpened(WindowEvent windowEvent) {

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            Dimension shotSize;

            shotSize = windowAncestor.getSize();
            int expectedX = (int) (shotSize.getWidth() / 4);
            int expectedY = (int) (shotSize.getHeight() / 4);
            int maxWidth = (int) (shotSize.getWidth() / 2);
            int maxHeight = (int) (shotSize.getHeight() / 2);
            boolean popupRes = checkImage(windowAncestor, shotSize, expectedX, expectedY, maxWidth, maxHeight);

            shotSize = modalBlocker.getContentPane().getSize();
            expectedX = (int) (shotSize.getWidth() / 2 + shotSize.getWidth() / 4);
            expectedY = (int) (shotSize.getHeight() / 2 + shotSize.getHeight() / 4);
            maxWidth = (int) (shotSize.getWidth());
            maxHeight = (int) (shotSize.getHeight());
            boolean modalBlockerRes = checkImage(modalBlocker.getContentPane(), shotSize, expectedX,
                    expectedY, maxWidth, maxHeight);

            String msg = "";

            if (!popupRes) msg = "The popup must be above the frame.";
            if (!modalBlockerRes) msg += "The modal blocker must be above the popup.";

            if (!popupRes || !modalBlockerRes)
                throw new RuntimeException(msg);

            modalBlocker.dispose();
            jFrame.dispose();
        }
    }
}
