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
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Panel;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/*
 * @test
 * @summary Verify mouse events on custom title bar area
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main MouseEventsOnClientArea
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main MouseEventsOnClientArea -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class MouseEventsOnClientArea {

    public static void main(String... args) {
        boolean status = CommonAPISuite.runTestSuite(TestUtils.getWindowCreationFunctions(), mouseClicks);

        if (!status) {
            throw new RuntimeException("MouseEventsOnClientArea FAILED");
        }
    }

    private static final Task mouseClicks = new Task("mouseClicks") {

        private static final List<Integer> BUTTON_MASKS = List.of(
                InputEvent.BUTTON1_DOWN_MASK,
                InputEvent.BUTTON2_DOWN_MASK,
                InputEvent.BUTTON3_DOWN_MASK
        );
        private static final int PANEL_WIDTH = 400;
        private static final int PANEL_HEIGHT = (int) TestUtils.TITLE_BAR_HEIGHT;

        private final boolean[] buttonsPressed = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsReleased = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsClicked = new boolean[BUTTON_MASKS.size()];
        private boolean mouseEntered;
        private boolean mouseExited;

        private Panel panel;

        @Override
        protected void init() {
            Arrays.fill(buttonsPressed, false);
            Arrays.fill(buttonsReleased, false);
            Arrays.fill(buttonsClicked, false);
            mouseEntered = false;
            mouseExited = false;
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        protected void customizeWindow() {
            panel = new Panel() {
                @Override
                public void paint(Graphics g) {
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.CYAN);
                    g.fillRect(r.x, r.y, PANEL_WIDTH, PANEL_HEIGHT);
                    super.paint(g);
                }
            };
            panel.setBounds(0, 0, PANEL_WIDTH, PANEL_HEIGHT);
            panel.setSize(PANEL_WIDTH, PANEL_HEIGHT);
            panel.addMouseListener(new MouseAdapter() {

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        buttonsClicked[e.getButton() - 1] = true;
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        buttonsPressed[e.getButton() - 1] = true;
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        buttonsReleased[e.getButton() - 1] = true;
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    mouseEntered = true;
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mouseExited = true;
                }
            });
            window.add(panel);
        }

        @Override
        public void test() throws AWTException {
            Robot robot = new Robot();

            BUTTON_MASKS.forEach(mask -> {
                robot.delay(500);

                robot.mouseMove(panel.getLocationOnScreen().x + panel.getWidth() / 2,
                        panel.getLocationOnScreen().y + panel.getHeight() / 2);
                robot.mousePress(mask);
                robot.mouseRelease(mask);

                robot.delay(500);
            });

            robot.delay(500);
            robot.mouseMove(panel.getLocationOnScreen().x + panel.getWidth() / 2,
                    panel.getLocationOnScreen().y + panel.getHeight() / 2);
            robot.delay(500);
            robot.mouseMove(panel.getLocationOnScreen().x + panel.getWidth() + 10,
                    panel.getLocationOnScreen().y + panel.getWidth() + 10);
            robot.delay(500);

            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                System.out.println("Button mask: " + BUTTON_MASKS.get(i));
                System.out.println("pressed = " + buttonsPressed[i]);
                System.out.println("released = " + buttonsReleased[i]);
                System.out.println("clicked = " + buttonsClicked[i]);
                passed = buttonsPressed[i] && buttonsReleased[i] && buttonsClicked[i];
            }

            if (!mouseEntered) {
                System.out.println("Error: mouse enter wasn't detected");
                passed = false;
            }
            if (!mouseExited) {
                System.out.println("Error: mouse exit wasn't detected");
                passed = false;
            }
        }
    };

}
