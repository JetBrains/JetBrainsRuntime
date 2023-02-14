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
import util.RectCoordinates;
import util.Task;
import util.ScreenShotHelpers;
import util.TestUtils;

import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

/*
 * @test
 * @summary Verify custom title bar in case of window resizing
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main WindowResizeTest
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main WindowResizeTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class WindowResizeTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), windowResizeTest);

        if (!status) {
            throw new RuntimeException("WindowResizeTest FAILED");
        }
    }

    private static final Task windowResizeTest = new Task("Window resize test") {
        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() throws Exception {
            Robot robot = new Robot();
            robot.delay(1000);
            final float initialTitleBarHeight = titleBar.getHeight();


            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            final int newHeight = screenSize.height / 2;
            final int newWidth = screenSize.width / 2;

            window.setSize(newWidth, newHeight);
            robot.delay(1000);

            if (titleBar.getHeight() != initialTitleBarHeight) {
                passed = false;
                System.out.println("Error: title bar height has been changed");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            RectCoordinates coords = ScreenShotHelpers.findRectangleTitleBar(image, (int) titleBar.getHeight());
            System.out.println("Planned title bar rectangle coordinates: (" + coords.x1() + ", " + coords.y1() +
                    "), (" + coords.x2() + ", " + coords.y2() + ")");
            System.out.println("w = " + image.getWidth() + " h = " + image.getHeight());
        }
    };

}
