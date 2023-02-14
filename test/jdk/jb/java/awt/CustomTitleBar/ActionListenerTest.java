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
