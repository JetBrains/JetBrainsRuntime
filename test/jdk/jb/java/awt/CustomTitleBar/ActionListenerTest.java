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
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.TitleBar.Task;
import test.jb.testhelpers.TitleBar.CommonAPISuite;
import test.jb.testhelpers.utils.MouseUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.lang.invoke.MethodHandles;
import java.util.List;

/*
 * @test
 * @summary Verify mouse events in custom title bar's area added by ActionListener
 * @requires (os.family == "windows" | os.family == "mac")
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 ActionListenerTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 ActionListenerTest
 */
public class ActionListenerTest {

    public static void main(String... args) {
        TaskResult awtResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createFrameWithCustomTitleBar, TestUtils::createDialogWithCustomTitleBar), actionListenerAWT);
        TaskResult swingResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createJFrameWithCustomTitleBar, TestUtils::createJFrameWithCustomTitleBar), actionListenerSwing);

        TaskResult result = awtResult.merge(swingResult);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task actionListenerAWT = new Task("Using of action listener in AWT") {

        private Button button;
        private boolean actionListenerGotEvent = false;

        @Override
        protected void init() {
            actionListenerGotEvent = false;
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void customizeWindow() {
            button = new Button();
            button.setLocation(window.getWidth() / 2, 0);
            button.addActionListener(a -> {
                actionListenerGotEvent = true;
            });
            window.add(button);
            window.setAlwaysOnTop(true);
        }

        @Override
        public void test() throws AWTException {
            doClick(window, button.getLocationOnScreen(), button.getWidth(), button.getHeight());

            if (!actionListenerGotEvent) {
                err("button didn't get event by action listener");
            }
        }
    };

    private static final Task actionListenerSwing = new Task("Using of action listener in Swing") {

        private JButton button;
        private boolean actionListenerGotEvent = false;

        @Override
        protected void init() {
            actionListenerGotEvent = false;
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void customizeWindow() {
            button = new JButton();
            button.setLocation(window.getWidth() / 2, 0);
            button.addActionListener(a -> {
                actionListenerGotEvent = true;
            });
            window.add(button);
            window.setAlwaysOnTop(true);
        }

        @Override
        public void test() throws AWTException {
            doClick(window, button.getLocationOnScreen(), button.getWidth(), button.getHeight());

            if (!actionListenerGotEvent) {
                err("button didn't get event by action listener");
            }
        }
    };

    private static void doClick(Window window, Point location, int w, int h) throws AWTException {
        Robot robot = new Robot();
        robot.waitForIdle();
        int x = location.x + w / 2;
        int y = location.y + h / 2;
        System.out.println("Click at (" + x + ", " + y + ")");

        MouseUtils.verifyLocationAndMove(robot, window, x, y);
        MouseUtils.verifyLocationAndClick(robot, window, x, y, InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        MouseUtils.verifyLocationAndClick(robot, window, x, y, InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
        MouseUtils.verifyLocationAndClick(robot, window, x, y, InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

}