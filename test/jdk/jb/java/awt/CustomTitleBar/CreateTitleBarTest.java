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
import util.Task;
import util.TestUtils;

/*
 * @test
 * @summary Verify creating to a custom title bar
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main CreateTitleBarTest
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main CreateTitleBarTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class CreateTitleBarTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), createTitleBar);

        if (!status) {
            throw new RuntimeException("CreateTitleBarTest FAILED");
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
                passed = false;
                System.out.println("Left or right space must be occupied by system controls");
            }
        }
    };

}
