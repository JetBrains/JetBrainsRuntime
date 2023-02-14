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

import java.awt.AWTException;
import java.awt.Button;
import java.awt.Robot;
import java.awt.event.InputEvent;

/*
 * @test
 * @summary Verify mouse events in custom title bar's area added by ActionListener
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main ActionListenerTest
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main ActionListenerTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class ActionListenerTest {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), actionListener);

        if (!status) {
            throw new RuntimeException("ActionListenerTest FAILED");
        }
    }

    private static final Task actionListener = new Task("Using of action listener") {

        private Button button;

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void customizeWindow() {
            button = new Button();
            button.setBounds(200, 20, 50, 50);
            button.addActionListener(a -> {
                System.out.println("Action listener got event");
            });
            window.add(button);
        }

        @Override
        public void test() throws AWTException {
            final int initialHeight = window.getHeight();
            final int initialWidth = window.getWidth();

            System.out.println("Initial bounds: " + window.getBounds());

            Robot robot = new Robot();
            robot.delay(500);
            int x = button.getLocationOnScreen().x + button.getWidth() / 2;
            int y = button.getLocationOnScreen().y + button.getHeight() / 2;
            System.out.println("Click at (" + x + ", " + y + ")");
            robot.mouseMove(x, y);
            robot.delay(300);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(2000);

            if (window.getHeight() != initialHeight || window.getWidth() != initialWidth) {
                passed = false;
                System.out.println("Adding of action listener should block native title bar behavior");
            }
        }
    };

}
