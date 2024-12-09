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

import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;
import util.CommonAPISuite;
import util.Task;
import util.TestUtils;

import java.awt.AWTException;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;

/*
 * @test
 * @summary Verify ability to maximize window by clicking to custom title bar area
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main MaximizeWindowTest
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main MaximizeWindowTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class MaximizeWindowTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), maximizeWindow);

        if (!status) {
            throw new RuntimeException("MaximizeWindowTest FAILED");
        }
    }

    private static final Task maximizeWindow = new Task("Maximize frame") {

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() throws AWTException {
            setResizable(true);
            setWindowSize(window, titleBar);
            int initialWidth = window.getWidth();
            int initialHeight = window.getHeight();
            doubleClickToTitleBar(window, titleBar);

            System.out.printf(String.format("Initial size (w = %d, h = %d)%n", window.getWidth(), window.getHeight()));
            System.out.printf(String.format("New size (w = %d, h = %d)%n", window.getWidth(), window.getHeight()));
            if (initialHeight == window.getHeight() && initialWidth == window.getWidth()) {
                passed = false;
                System.out.println("Frame size wasn't changed");
            }

            setResizable(false);
            setWindowSize(window, titleBar);
            initialWidth = window.getWidth();
            initialHeight = window.getHeight();
            doubleClickToTitleBar(window, titleBar);
            System.out.printf(String.format("Initial size (w = %d, h = %d)%n", window.getWidth(), window.getHeight()));
            System.out.printf(String.format("New size (w = %d, h = %d)%n", window.getWidth(), window.getHeight()));
            if (initialHeight != window.getHeight() || initialWidth != window.getWidth()) {
                passed = false;
                System.out.println("Frame size was changed");
            }
        }

        private void setResizable(boolean value) {
            if (window instanceof Frame frame) {
                frame.setResizable(value);
            } else if (window instanceof Dialog dialog) {
                dialog.setResizable(value);
            }
        }
    };


    private static void setWindowSize(Window window, WindowDecorations.CustomTitleBar titleBar) {
        System.out.println("Window was created with bounds: " + window.getBounds());

        int w = window.getBounds().width / 2;
        int h = window.getBounds().height / 2;
        window.setBounds(window.getBounds().x, window.getBounds().y, w, h);

        System.out.println("New window bounds: " + window.getBounds());
        window.setSize(w, h);
    }

    private static void doubleClickToTitleBar(Window window, WindowDecorations.CustomTitleBar titleBar) throws AWTException {
        int leftX = window.getInsets().left + (int) titleBar.getLeftInset();
        int rightX = window.getBounds().width - window.getInsets().right - (int) titleBar.getRightInset();
        int x = window.getLocationOnScreen().x + (rightX - leftX) / 2;
        int topY = window.getInsets().top;
        int bottomY = (int) TestUtils.TITLE_BAR_HEIGHT;
        int y = window.getLocationOnScreen().y + (bottomY - topY) / 2;

        System.out.println("Window bounds: " + window.getBounds());
        System.out.println("Window insets: " + window.getInsets());
        System.out.println("Window location on screen: " + window.getLocationOnScreen());
        System.out.println("leftX = " + leftX + ", rightX = " + rightX);
        System.out.println("topY = " + topY + ", bottomY = " + bottomY);
        System.out.println("Double click to (" + x + ", " + y + ")");

        Robot robot = new Robot();

        robot.delay(1000);
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(1000);
    }

}
