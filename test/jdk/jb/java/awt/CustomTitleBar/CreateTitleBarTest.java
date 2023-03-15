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

import java.lang.invoke.MethodHandles;

/*
 * @test
 * @summary Verify creating to a custom title bar
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 CreateTitleBarTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 CreateTitleBarTest
 */
public class CreateTitleBarTest {

    public static void main(String... args) {
        TaskResult result = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), createTitleBar);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }

    }

    private static final Task createTitleBar = new Task("Create title bar with default settings") {

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void test() {
            passed = passed && TestUtils.checkTitleBarHeight(titleBar, TestUtils.TITLE_BAR_HEIGHT);
            passed = passed && TestUtils.checkFrameInsets(window);

            if (titleBar.getLeftInset() == 0 && titleBar.getRightInset() == 0) {
                err("Left or right space must be occupied by system controls");
            }
        }
    };

}