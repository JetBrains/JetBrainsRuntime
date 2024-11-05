/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;


/**
 * @test
 * @summary Verifies that the minimum size of a popup-style window can be changed
 *           immediately after it has been made visible under Wayland
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main WLPopupMinSize
 */
public class WLPopupMinSize {
    private static JFrame frame;
    private static Window popup;

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        try {
            SwingUtilities.invokeAndWait(WLPopupMinSize::createAndShowUI);
            robot.delay(500);
            SwingUtilities.invokeAndWait(WLPopupMinSize::createAndShowPopup);
            robot.delay(500);

            Dimension popupMinSize = popup.getMinimumSize();
            if (popupMinSize.width != 1000 || popupMinSize.height != 1000) {
                throw new RuntimeException("Popup minimum size is not 1000x100 but " + popupMinSize);
            }

            Dimension popupSize = popup.getSize();
            System.out.println("Popup size: " + popupSize);
            if (popupSize.width != 1000 || popupSize.height != 1000) {
                throw new RuntimeException("Popup actual  size is not 1000x100 but " + popupSize);
            }
        } finally {
            frame.dispose();
        }
    }

    static void createAndShowUI() {
        frame = new JFrame("Popup Minimum Size Test");
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    static void createAndShowPopup() {
        popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, frame);
        popup.setSize(50, 50);
        popup.setVisible(true);

        // Setting the minimum size at this point must resize the popup window
        popup.setMinimumSize(new Dimension(1000, 1000));
    }
}