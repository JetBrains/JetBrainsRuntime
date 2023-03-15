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

import java.awt.Color;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;
import java.util.List;

/*
 * @test
 * @summary Verify a property to change visibility of native controls
 * @requires os.family == "windows"
 * @run main/othervm WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 WindowsControlWidthTest
 * @run main/othervm  -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 WindowsControlWidthTest
 */
public class WindowsControlWidthTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), nativeControlsWidth);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
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
                err("Left or right inset must be non-zero");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            List<Rect> foundControls = ScreenShotHelpers.findControls(image, window, titleBar);

            if (foundControls.size() == 0) {
                err("controls not found");
            } else if (foundControls.size() == 3) {
                System.out.println("3 controls found");
                int minX = foundControls.get(0).getX1();
                int maxX = foundControls.get(2).getX2();
                int dist = (foundControls.get(1).getX1() - foundControls.get(0).getX2() + foundControls.get(2).getX1() - foundControls.get(1).getX2()) / 2;
                int calculatedWidth = maxX - minX + dist;
                float diff = (float) calculatedWidth / CONTROLS_WIDTH;
                if (diff < 0.95 || diff > 1.05) {
                    err("control's width is much differ than the expected value");
                }
            } else if (foundControls.size() == 1){
                System.out.println("1 control found");
                int calculatedWidth = foundControls.get(0).getX2() - foundControls.get(0).getX1();
                if (calculatedWidth < 0.5) {
                    err("control's width is much differ than the expected value");
                }
            } else {
                err("unexpected controls count = " + foundControls.size());
            }
        }

    };

}