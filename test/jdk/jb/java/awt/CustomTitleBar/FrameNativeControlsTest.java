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
import test.jb.testhelpers.screenshot.ScreenShotHelpers;
import test.jb.testhelpers.screenshot.Rect;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.TitleBar.Task;
import test.jb.testhelpers.TitleBar.CommonAPISuite;

import javax.swing.JFrame;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.event.WindowStateListener;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Function;

/*
 * @test
 * @summary Detect and check behavior of clicking to native controls
 * @requires (os.family == "windows") | (os.family == "mac")
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 FrameNativeControlsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 FrameNativeControlsTest
 */
public class FrameNativeControlsTest {

    public static void main(String... args) {
        List<Function<WindowDecorations.CustomTitleBar, Window>> functions = List.of(TestUtils::createFrameWithCustomTitleBar);
        TaskResult result = CommonAPISuite.runTestSuite(functions, frameNativeControlsClicks);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task frameNativeControlsClicks = new Task("Frame native controls clicks") {
        private boolean closingActionCalled;
        private boolean iconifyingActionCalled;
        private boolean maximizingActionDetected;
        private boolean deiconifyindActionDetected;

        private final WindowListener windowListener = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closingActionCalled = true;
            }

            @Override
            public void windowIconified(WindowEvent e) {
                iconifyingActionCalled = true;

                if (window.getName().equals("Frame")) {
                    ((Frame) window).setState(Frame.NORMAL);
                } else if (window.getName().equals("JFrame")) {
                    ((JFrame) window).setState(JFrame.NORMAL);
                }

                window.setVisible(true);
                window.requestFocus();
            }
        };

        private final WindowStateListener windowStateListener = new WindowAdapter() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                System.out.println("change " + e.getOldState() + " -> " + e.getNewState());
                if (e.getNewState() == 6) {
                    maximizingActionDetected = true;
                }
                if (e.getOldState() == 1 && e.getNewState() == 0) {
                    deiconifyindActionDetected = true;
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
            closingActionCalled = false;
            iconifyingActionCalled = false;
            maximizingActionDetected = false;
            deiconifyindActionDetected = false;
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
            robot.delay(500);
            robot.mouseMove(window.getLocationOnScreen().x + window.getWidth() / 2,
                    window.getLocationOnScreen().y + window.getHeight() / 2);
            robot.delay(500);

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);
            List<Rect> foundControls = ScreenShotHelpers.findControls(image, window, titleBar);

            if (foundControls.isEmpty()) {
                passed = false;
                System.out.println("Error: no controls found");
            }

            foundControls.forEach(control -> {
                System.out.println("Using control: " + control);
                int x = window.getLocationOnScreen().x + control.getX1() + (control.getX2() - control.getX1()) / 2;
                int y = window.getLocationOnScreen().y + control.getY1() + (control.getY2() - control.getY1()) / 2;
                System.out.println("Click to (" + x + ", " + y + ")");

                int screenX = window.getBounds().x;
                int screenY = window.getBounds().y;
                int h = window.getBounds().height;
                int w = window.getBounds().width;

                robot.waitForIdle();
                robot.mouseMove(x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.waitForIdle();
                window.setBounds(screenX, screenY, w, h);
                window.setVisible(true);
                robot.waitForIdle();
            });

            if (!maximizingActionDetected) {
                err("maximizing action was not detected");
            }

            if (!closingActionCalled) {
                err("closing action was not detected");
            }

            if (!iconifyingActionCalled) {
                err("iconifying action was not detected");
            }
            if (!deiconifyindActionDetected) {
                err("deiconifying action was not detected");
            }
        }
    };

}