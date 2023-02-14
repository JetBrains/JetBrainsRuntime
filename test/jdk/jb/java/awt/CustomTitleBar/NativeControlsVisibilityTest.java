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

//            List<Rect> foundControls = ScreenShotHelpers.detectControls(image, (int) titleBar.getHeight(),
//                    (int) titleBar.getLeftInset(), (int) titleBar.getRightInset());
            List<Rect> foundControls = ScreenShotHelpers.detectControlsByBackground(image, (int) titleBar.getHeight(), TestUtils.TITLE_BAR_COLOR);
            System.out.println("Found controls at the title bar:");
            foundControls.forEach(System.out::println);

            if (foundControls.size() != 3) {
                passed = false;
                System.out.println("Error: there are must be 3 controls");
            }

            if (!passed) {
                String path = ScreenShotHelpers.storeScreenshot("visible-controls-test-" + window.getName(), image);
                System.out.println("Screenshot stored in " + path);
            }
        }
    };




}
