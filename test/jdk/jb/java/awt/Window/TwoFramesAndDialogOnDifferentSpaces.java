/*
 * Copyright 2000-2022 JetBrains s.r.o.
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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Specific case for JBR-4186 Unexpected desktop switch after moving a window to another desktop
 * @key headful
 * @requires (os.family == "mac")
 * @compile MacSpacesUtil.java
 * @run main TwoFramesAndDialogOnDifferentSpaces
 */

public class TwoFramesAndDialogOnDifferentSpaces {
    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static JDialog dialog;

    public static void main(String[] args) throws Exception {
        MacSpacesUtil.ensureMoreThanOneSpaceExists();
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(TwoFramesAndDialogOnDifferentSpaces::initUI);
            robot.delay(1000);
            ensureVisibility(true, true, true);
            MacSpacesUtil.moveWindowToNextSpace(frame1);
            ensureVisibility(true, false, false);
            MacSpacesUtil.switchToPreviousSpace();
            ensureVisibility(false, true, true);
            clickOn(frame2);
            robot.delay(1000);
            MacSpacesUtil.switchToNextSpace();
            ensureVisibility(true, false, false);
        } finally {
            MacSpacesUtil.switchToPreviousSpace();
            SwingUtilities.invokeAndWait(TwoFramesAndDialogOnDifferentSpaces::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("TFADODS-1");
        frame1.setBounds(200, 200, 300, 100);
        frame1.setVisible(true);

        frame2 = new JFrame("TFADODS-2");
        frame2.setBounds(200, 400, 300, 100);
        frame2.setVisible(true);

        dialog = new JDialog(frame2, "TFADODS");
        dialog.setBounds(200, 600, 300, 100);
        dialog.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
    }

    private static void ensureVisibility(boolean frame1Visible, boolean frame2Visible, boolean dialogVisible)
            throws Exception {
        if (frame1Visible != MacSpacesUtil.isWindowVisible(frame1)) {
            throw new RuntimeException("Frame 1 is " + (frame1Visible ? "not " : "") + " visible");
        }
        if (frame2Visible != MacSpacesUtil.isWindowVisible(frame2)) {
            throw new RuntimeException("Frame 2 is " + (frame2Visible ? "not " : "") + " visible");
        }
        if (dialogVisible != MacSpacesUtil.isWindowVisible(dialog)) {
            throw new RuntimeException("Dialog is " + (dialogVisible ? "not " : "") + " visible");
        }
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
