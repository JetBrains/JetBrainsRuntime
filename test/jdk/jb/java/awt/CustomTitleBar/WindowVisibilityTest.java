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
import util.Task;
import util.TaskResult;
import util.TestUtils;

import java.awt.Robot;
import java.lang.invoke.MethodHandles;

/*
 * @test
 * @summary Verify custom title bar in case of changing visibility of a window
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 WindowVisibilityTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 WindowVisibilityTest
 */
public class WindowVisibilityTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), visibilityTest);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task visibilityTest = new Task("visibilityTest") {

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() throws Exception {
            Robot robot = new Robot();

            final float titleBarHeight = titleBar.getHeight();

            window.setVisible(false);
            robot.delay(1000);

            window.setVisible(true);
            robot.delay(1000);

            if (titleBarHeight != titleBar.getHeight()) {
                err("Error: title bar height has been changed");
            }
            if (!titleBar.getContainingWindow().equals(window)) {
                err("wrong containing window");
            }
        }

    };

}