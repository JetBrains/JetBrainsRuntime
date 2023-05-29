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
import util.*;

import javax.swing.*;
import java.awt.AWTException;
import java.awt.Button;
import java.awt.Color;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;

/*
 * @test
 * @summary Verify control under native actions in custom title bar
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 HitTestNonClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 HitTestNonClientArea

 */
public class HitTestNonClientArea {

    private static final List<Integer> BUTTON_MASKS = List.of(
            InputEvent.BUTTON1_DOWN_MASK,
            InputEvent.BUTTON2_DOWN_MASK,
            InputEvent.BUTTON3_DOWN_MASK
    );
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 40;


    public static void main(String... args) {
        TaskResult awtResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createFrameWithCustomTitleBar, TestUtils::createDialogWithCustomTitleBar), hitTestNonClientAreaAWT);
        TaskResult swingResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createJFrameWithCustomTitleBar, TestUtils::createJFrameWithCustomTitleBar), hitTestNonClientAreaSwing);

        TaskResult result = awtResult.merge(swingResult);
        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }


    private static final Task hitTestNonClientAreaAWT = new AWTTask("Hit test non-client area AWT") {

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
                MouseUtils.verifyLocationAndClick(robot, window, initialX, initialY, mask);
            }

            Point initialLocation = window.getLocationOnScreen();
            robot.waitForIdle();
            MouseUtils.verifyLocationAndMove(robot, window, initialX, initialY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 0; i < 10; i++) {
                initialX += 3;
                initialY += 3;
                robot.delay(300);
                MouseUtils.verifyLocationAndMove(robot, window, initialX, initialY);
            }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            Point newLocation = window.getLocationOnScreen();

            passed = initialLocation.x < newLocation.x && initialLocation.y < newLocation.y;
            if (!passed) {
                System.out.println("Window location was changed");
            }
            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                if (!gotClicks[i]) {
                    err("Mouse click to button no " + (i+1) + " was not registered");
                }
            }
        }
    };

    private static final Task hitTestNonClientAreaSwing = new SwingTask("Hit test non-client area Swing") {

        private final boolean[] gotClicks = new boolean[BUTTON_MASKS.size()];
        private JButton button;

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
            button = new JButton();
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
            window.setAlwaysOnTop(true);
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
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            Point newLocation = window.getLocationOnScreen();

            passed = initialLocation.x < newLocation.x && initialLocation.y < newLocation.y;
            if (!passed) {
                System.out.println("Window location was changed");
            }
            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                if (!gotClicks[i]) {
                    err("Mouse click to button no " + (i+1) + " was not registered");
                }
            }
        }
    };

}