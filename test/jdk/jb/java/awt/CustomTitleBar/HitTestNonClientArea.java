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
import java.awt.Color;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/*
 * @test
 * @summary Verify control under native actions in custom title bar
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main HitTestNonClientArea
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main HitTestNonClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class HitTestNonClientArea {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), hitTestNonClientArea);

        if (!status) {
            throw new RuntimeException("HitTestNonClientArea FAILED");
        }
    }


    private static final Task hitTestNonClientArea = new Task("Hit test non-client area") {

        private static final List<Integer> BUTTON_MASKS = List.of(
                InputEvent.BUTTON1_DOWN_MASK,
                InputEvent.BUTTON2_DOWN_MASK,
                InputEvent.BUTTON3_DOWN_MASK
        );
        private static final int BUTTON_WIDTH = 80;
        private static final int BUTTON_HEIGHT = 40;

        private final boolean[] gotClicks = new boolean[BUTTON_MASKS.size()];
        private Button button;

        @Override
        protected void cleanup() {
            Arrays.fill(gotClicks, false);
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        public void customizeWindow() {
            button = new Button();
            button.setBackground(Color.CYAN);
            button.setSize(BUTTON_WIDTH, BUTTON_HEIGHT);
            MouseAdapter adapter = new MouseAdapter() {
                private void hit() {
                    titleBar.forceHitTest(false);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    hit();
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        gotClicks[e.getButton() - 1] = true;
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    hit();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    hit();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hit();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    hit();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    hit();
                }
            };
            button.addMouseListener(adapter);
            button.addMouseMotionListener(adapter);

            Panel panel = new Panel();
            panel.setBounds(300, 20, 100, 50);
            panel.add(button);
            window.add(panel);
        }

        @Override
        public void test() throws AWTException {
            Robot robot = new Robot();

            BUTTON_MASKS.forEach(mask -> {
                robot.delay(500);

                robot.mouseMove(button.getLocationOnScreen().x + button.getWidth() / 2,
                        button.getLocationOnScreen().y + button.getHeight() / 2);
                robot.mousePress(mask);
                robot.mouseRelease(mask);

                robot.delay(500);
            });

            Point initialLocation = window.getLocationOnScreen();
            robot.delay(500);
            int initialX = button.getLocationOnScreen().x + button.getWidth() / 2;
            int initialY = button.getLocationOnScreen().y + button.getHeight() / 2;
            robot.mouseMove(initialX, initialY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 0; i < 10; i++) {
                initialX += 3;
                initialY += 3;
                robot.delay(500);
                robot.mouseMove(initialX, initialY);
            }
            robot.delay(500);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Point newLocation = window.getLocationOnScreen();

            passed = initialLocation.x < newLocation.x && initialLocation.y < newLocation.y;
            if (!passed) {
                System.out.println("Window location was changed");
            }
            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                if (!gotClicks[i]) {
                    System.out.println("Mouse click to button no " + (i+1) + " was not registered");
                    passed = false;
                }
            }
        }
    };

}
