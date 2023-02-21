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
import util.CommonAPISuite;
import util.Rect;
import util.Task;
import util.ScreenShotHelpers;
import util.TestUtils;

import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.List;

/*
 * @test
 * @summary Verify a property to change visibility of native controls
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main NativeControlsVisibilityTest
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main NativeControlsVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class NativeControlsVisibilityTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), visibleControls);

        if (!status) {
            throw new RuntimeException("NativeControlsVisibilityTest FAILED");
        }
    }

    private static final Task visibleControls = new Task("Visible native controls") {
        private static final String PROPERTY_NAME = "controls.visible";
        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() throws Exception {
            Robot robot = new Robot();
            robot.delay(500);
            robot.mouseMove(window.getLocationOnScreen().x + window.getWidth() / 2,
                    window.getLocationOnScreen().y + window.getHeight() / 2);
            robot.delay(500);

            passed = passed && TestUtils.checkTitleBarHeight(titleBar, TestUtils.TITLE_BAR_HEIGHT);
            passed = passed && TestUtils.checkFrameInsets(window);

            if (titleBar.getLeftInset() == 0 && titleBar.getRightInset() == 0) {
                passed = false;
                System.out.println("Left or right inset must be non-zero");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            List<Rect> foundControls = ScreenShotHelpers.detectControlsByBackground(image, (int) titleBar.getHeight(), TestUtils.TITLE_BAR_COLOR);
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
