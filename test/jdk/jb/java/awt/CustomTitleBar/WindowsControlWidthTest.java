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
