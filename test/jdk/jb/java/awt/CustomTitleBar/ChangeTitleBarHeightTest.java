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
import test.jb.testhelpers.TitleBar.CommonAPISuite;
import test.jb.testhelpers.TitleBar.TaskResult;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.TitleBar.Task;

import java.lang.invoke.MethodHandles;

/*
 * @test
 * @summary Verify modifying of title bar height
 * @requires (os.family == "windows" | os.family == "mac")
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm ChangeTitleBarHeightTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 ChangeTitleBarHeightTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 ChangeTitleBarHeightTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 ChangeTitleBarHeightTest
 */
public class ChangeTitleBarHeightTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), changeTitleBarHeight);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }

    }

    private static final Task changeTitleBarHeight = new Task("Changing of title bar height") {

        private final float initialHeight = 50;

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(initialHeight);
        }

        @Override
        public void test() {
            passed = passed && TestUtils.checkTitleBarHeight(titleBar, initialHeight);

            float newHeight = 100;
            titleBar.setHeight(newHeight);
            passed = passed && TestUtils.checkTitleBarHeight(titleBar, newHeight);
        }
    };

}