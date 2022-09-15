/*
 * Copyright 2000-2021 JetBrains s.r.o.
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
    boolean stateChanged;

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
