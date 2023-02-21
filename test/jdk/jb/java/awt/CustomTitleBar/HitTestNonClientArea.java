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
            titleBar = null;
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

            int initialX = button.getLocationOnScreen().x + button.getWidth() / 2;
            int initialY = button.getLocationOnScreen().y + button.getHeight() / 2;

            for (Integer mask: BUTTON_MASKS) {
                robot.waitForIdle();

                robot.mouseMove(initialX, initialY);
                robot.mousePress(mask);
                robot.mouseRelease(mask);

                robot.waitForIdle();
            }

            Point initialLocation = window.getLocationOnScreen();
            robot.waitForIdle();
            robot.mouseMove(initialX, initialY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 0; i < 10; i++) {
                initialX += 3;
                initialY += 3;
                robot.delay(300);
                robot.mouseMove(initialX, initialY);
            }
            robot.waitForIdle();
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
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
