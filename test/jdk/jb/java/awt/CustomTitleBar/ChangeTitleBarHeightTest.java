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
 * @summary Verify modifying of title bar height
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main ChangeTitleBarHeightTest
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main ChangeTitleBarHeightTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class ChangeTitleBarHeightTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), changeTitleBarHeight);

        if (!status) {
            throw new RuntimeException("ChangeTitleBarHeightTest FAILED");
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
