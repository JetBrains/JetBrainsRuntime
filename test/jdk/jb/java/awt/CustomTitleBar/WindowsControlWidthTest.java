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
import util.ScreenShotHelpers;
import util.Task;
import util.TestUtils;

import java.awt.Color;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.List;

/*
 * @test
 * @summary Verify a property to change visibility of native controls
 * @requires os.family == "windows"
 * @run main WindowsControlWidthTest
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 10" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 * @run main WindowsControlWidthTest -Dos.version=10 -Dos.name="Windows 11" -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class WindowsControlWidthTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), nativeControlsWidth);

        if (!status) {
            throw new RuntimeException("WindowsControlWidthTest FAILED");
        }
    }

    private static final Task nativeControlsWidth = new Task("Native controls width") {

        private static final String WIDTH_PROPERTY = "controls.width";
        private static final int CONTROLS_WIDTH = 80;

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
            titleBar.putProperty(WIDTH_PROPERTY, CONTROLS_WIDTH);
        }

        @Override
        public void test() throws Exception {
            passed = passed && TestUtils.checkTitleBarHeight(titleBar, TestUtils.TITLE_BAR_HEIGHT);
            passed = passed && TestUtils.checkFrameInsets(window);

            if (titleBar.getLeftInset() == 0 && titleBar.getRightInset() == 0) {
                passed = false;
                System.out.println("Left or right inset must be non-zero");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            List<Rect> foundControls = ScreenShotHelpers.detectControlsByBackground(image, (int) titleBar.getHeight(), TestUtils.TITLE_BAR_COLOR);

            foundControls.forEach(control -> {
                System.out.println("Detected control: " + control);
                int calculatedWidth = control.getY2() - control.getY1();
                System.out.println("Calculated width: " + calculatedWidth);

                float diff = (float) calculatedWidth / (float) (control.getX2() - control.getX1());
                if (diff < 0.9 || diff > 1.1) {
                    System.out.println("Error: control's width is much differ than the expected value");
                    passed = false;
                }
            });
        }

    };

}
