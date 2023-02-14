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

import java.awt.Robot;

/*
 * @test
 * @summary Verify custom title bar in case of changing visibility of a window
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main WindowVisibilityTest
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main WindowVisibilityTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class WindowVisibilityTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), visibilityTest);

        if (!status) {
            throw new RuntimeException("WindowVisibilityTest FAILED");
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
                passed = false;
                System.out.println("Error: title bar height has been changed");
            }
            if (!titleBar.getContainingWindow().equals(window)) {
                passed = false;
                System.out.println("Error: wrong containing window");
            }
        }

    };

}
