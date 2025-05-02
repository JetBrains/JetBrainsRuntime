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
import util.*;
import test.jb.testhelpers.screenshot.ScreenShotHelpers;
import test.jb.testhelpers.screenshot.Rect;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.TitleBar.Task;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.event.WindowStateListener;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;
import java.util.List;
import test.jb.testhelpers.utils.MouseUtils;

/*
 * @test
 * @summary Detect and check behavior of clicking to native controls
 * @requires os.family == "mac"
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 MinimizingWindowTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 MinimizingWindowTest
 */
public class MinimizingWindowTest {

    public static void main(String... args) {
        TaskResult result = minimizingWindowTest.run(TestUtils::createFrameWithCustomTitleBar);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task minimizingWindowTest = new Task("Frame native controls clicks") {
        private boolean iconifyingActionCalled;
        private boolean iconifyingActionDetected;

        private final WindowListener windowListener = new WindowAdapter() {

            @Override
            public void windowIconified(WindowEvent e) {
                iconifyingActionCalled = true;
            }
        };

        private final WindowStateListener windowStateListener = new WindowAdapter() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                System.out.println("change " + e.getOldState() + " -> " + e.getNewState());
                if (e.getOldState() == 0 && e.getNewState() == 1) {
                    iconifyingActionDetected = true;
					((Frame) window).setState(Frame.NORMAL);
					window.setVisible(true);
                }
            }
        };

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        protected void init() {
            iconifyingActionCalled = false;
            iconifyingActionDetected = false;
        }

        @Override
        protected void cleanup() {
            window.removeWindowListener(windowListener);
            window.removeWindowStateListener(windowStateListener);
        }

        @Override
        protected void customizeWindow() {
            window.addWindowListener(windowListener);
            window.addWindowStateListener(windowStateListener);
        }

        @Override
        public void test() throws Exception {
            robot.waitForIdle();

            MouseUtils.verifyLocationAndMove(robot, window,
                    window.getLocationOnScreen().x + window.getWidth() / 2,
                    window.getLocationOnScreen().y + window.getHeight() / 2);
            robot.waitForIdle();

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);
            List<Rect> foundControls = ScreenShotHelpers.findControls(image, window, titleBar);

            if (foundControls.isEmpty()) {
                err("no controls found");
            }

            int screenX = window.getBounds().x;
            int screenY = window.getBounds().y;
            int h = window.getBounds().height;
            int w = window.getBounds().width;
            int locationX = window.getLocationOnScreen().x;
            int locationY = window.getLocationOnScreen().y;

            foundControls.forEach(control -> {
                System.out.println("Using control: " + control);
                int x = locationX + control.getX1() + (control.getX2() - control.getX1()) / 2;
                int y = locationY + control.getY1() + (control.getY2() - control.getY1()) / 2;
                System.out.println("Click to (" + x + ", " + y + ")");

                robot.waitForIdle();
                MouseUtils.verifyLocationAndMove(robot, window, x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.waitForIdle();
                robot.delay(500);
                window.setBounds(screenX, screenY, w, h);
                window.setVisible(true);
                robot.waitForIdle();
            });

            if (!iconifyingActionCalled || !iconifyingActionDetected) {
                err("iconifying action was not detected");
            }

            if (!passed) {
                String path = ScreenShotHelpers.storeScreenshot("minimizing-window-test-" + window.getName(), image);
                System.out.println("Screenshot stored in " + path);
            }
        }
    };

}