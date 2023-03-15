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

import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;

/*
 * @test
 * @summary Verify custom title bar in case of window resizing
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 WindowResizeTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 WindowResizeTest
 */
public class WindowResizeTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), windowResizeTest);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
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
                err("title bar height has been changed");
            }

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);

            RectCoordinates coords = ScreenShotHelpers.findRectangleTitleBar(image, (int) titleBar.getHeight());
            System.out.println("Planned title bar rectangle coordinates: (" + coords.x1() + ", " + coords.y1() +
                    "), (" + coords.x2() + ", " + coords.y2() + ")");
            System.out.println("w = " + image.getWidth() + " h = " + image.getHeight());
        }
    };

}