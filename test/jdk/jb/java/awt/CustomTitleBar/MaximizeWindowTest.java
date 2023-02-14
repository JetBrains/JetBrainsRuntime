/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
