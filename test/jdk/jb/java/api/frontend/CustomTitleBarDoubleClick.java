/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

/*
  @test
  @requires os.family == "mac" | os.family == "windows"
  @key headful
  @summary Test that window state changes on titlebar double-click
  @library    ../../../../java/awt/regtesthelpers
  @build      Util
  @run shell build.sh
  @run main CustomTitleBarDoubleClick
*/

import com.jetbrains.JBR;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import test.java.awt.regtesthelpers.Util;

public class CustomTitleBarDoubleClick implements WindowListener, WindowStateListener {
    //Declare things used in the test, like buttons and labels here
    private final static Rectangle BOUNDS = new Rectangle(300, 300, 300, 300);
    private final static int TITLE_BAR_OFFSET = 10;

    JFrame frame;
    Robot robot;
    volatile boolean stateChanged;

    public static void main(final String[] args) {
        CustomTitleBarDoubleClick app = new CustomTitleBarDoubleClick();
        app.start();
    }

    public void start () {
        robot = Util.createRobot();
        robot.setAutoDelay(100);
        robot.mouseMove(BOUNDS.x + (BOUNDS.width / 2),
                        BOUNDS.y + (BOUNDS.height/ 2));

        frame = new JFrame("CustomTitleBarDoubleClick"); // Custom decorations doesn't work for AWT Frames on macOS
        frame.setBounds(BOUNDS);
        frame.addWindowListener(this);
        frame.addWindowStateListener(this);
        JBR.getCustomWindowDecoration().setCustomDecorationEnabled(frame, true);
        JBR.getCustomWindowDecoration().setCustomDecorationTitleBarHeight(frame, 50);
        frame.setVisible(true);
        robot.delay(2000);
        if (!stateChanged) throw new AWTError("Test failed");
    }

    // Move the mouse into the title bar and double click to maximize the Frame
    static boolean hasRun = false;

    private void doTest() {
        if (hasRun) return;
        hasRun = true;

        System.out.println("doing test");
        robot.mouseMove(BOUNDS.x + (BOUNDS.width / 2),
                        BOUNDS.y + TITLE_BAR_OFFSET);
        robot.delay(50);
        // Util.waitForIdle(robot) seem always hangs here.
        // Need to use it instead robot.delay() when the bug become fixed.
        System.out.println("1st press:   currentTimeMillis: " + System.currentTimeMillis());
        robot.mousePress(InputEvent.BUTTON1_MASK);
        robot.delay(50);
        System.out.println("1st release: currentTimeMillis: " + System.currentTimeMillis());
        robot.mouseRelease(InputEvent.BUTTON1_MASK);
        robot.delay(50);
        System.out.println("2nd press:   currentTimeMillis: " + System.currentTimeMillis());
        robot.mousePress(InputEvent.BUTTON1_MASK);
        robot.delay(50);
        System.out.println("2nd release: currentTimeMillis: " + System.currentTimeMillis());
        robot.mouseRelease(InputEvent.BUTTON1_MASK);
        System.out.println("done:        currentTimeMillis: " + System.currentTimeMillis());
    }

    public void windowActivated(WindowEvent  e) {doTest();}
    public void windowClosed(WindowEvent  e) {}
    public void windowClosing(WindowEvent  e) {}
    public void windowDeactivated(WindowEvent  e) {}
    public void windowDeiconified(WindowEvent  e) {}
    public void windowIconified(WindowEvent  e) {}
    public void windowOpened(WindowEvent  e) {}

    public void windowStateChanged(WindowEvent e) {
        stateChanged = true; // On macOS double-click may cause window to be maximized or iconified depending on AppleActionOnDoubleClick setting
    }

}
