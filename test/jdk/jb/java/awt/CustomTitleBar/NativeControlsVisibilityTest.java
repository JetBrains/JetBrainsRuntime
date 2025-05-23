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
import test.jb.testhelpers.screenshot.ScreenShotHelpers;
import test.jb.testhelpers.screenshot.Rect;
import test.jb.testhelpers.TitleBar.CommonAPISuite;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.TitleBar.Task;
import java.awt.Robot;
import java.util.List;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;
import test.jb.testhelpers.utils.MouseUtils;

/*
 * @test
 * @summary Verify a property to change visibility of native controls
 * @requires os.family == "mac"
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm NativeControlsVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 NativeControlsVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 NativeControlsVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 NativeControlsVisibilityTest
 */
public class NativeControlsVisibilityTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), visibleControls);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task visibleControls = new Task("Visible native controls") {
        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() throws Exception {
            Robot robot = new Robot();
            robot.delay(500);
            MouseUtils.verifyLocationAndMove(robot, window,
                    window.getLocationOnScreen().x + window.getWidth() / 2,
                    window.getLocationOnScreen().y + window.getHeight() / 2);
            robot.delay(500);

            passed = passed && TestUtils.checkTitleBarHeight(titleBar, TestUtils.TITLE_BAR_HEIGHT);
            passed = passed && TestUtils.checkFrameInsets(window);

            if (titleBar.getLeftInset() == 0 && titleBar.getRightInset() == 0) {
                passed = false;
                System.out.println("Left or right inset must be non-zero");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            List<Rect> foundControls = ScreenShotHelpers.findControls(image, window, titleBar, false);
            System.out.println("Found controls at the title bar:");
            foundControls.forEach(System.out::println);

            passed = verifyControls(foundControls, window.getName());

            if (!passed) {
                String path = ScreenShotHelpers.storeScreenshot("visible-controls-test-" + window.getName(), image);
                System.out.println("Screenshot stored in " + path);
            }
        }

        private static boolean verifyControls(List<Rect> foundControls, String windowName) {
            final String os = System.getProperty("os.name").toLowerCase();
            if (os.startsWith("windows")) {
                return verifyControlsOnWindows(foundControls, windowName);
            } else if (os.startsWith("mac os")) {
                return verifyControlsOnMac(foundControls);
            }
            return true;
        }

        private static boolean verifyControlsOnWindows(List<Rect> foundControls, String windowName) {
            if (windowName.equals("Frame") || windowName.equals("JFrame")) {
                if (foundControls.size() != 3) {
                    System.out.println("Error: there are must be 3 controls");
                    return false;
                }
            } else if (windowName.equals("JDialog") || windowName.equals("Dialog")) {
                if (foundControls.size() != 1) {
                    System.out.println("Error: there are must be 1 control");
                    return false;
                }
            }
            return true;
        }

        private static boolean verifyControlsOnMac(List<Rect> foundControls) {
            if (foundControls.size() != 3) {
                System.out.println("Error: there are must be 3 controls");
                return false;
            }
            return true;
        }
    };




}