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

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Rectangle;
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
 * @run main/othervm HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 HitTestClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 HitTestClientArea
 */
public class HitTestClientArea {

    public static void main(String... args) {
        boolean awtStatus = CommonAPISuite.runTestSuite(List.of(TestUtils::createFrameWithCustomTitleBar, TestUtils::createDialogWithCustomTitleBar), hitTestClientAreaAWT);
        boolean swingStatus = CommonAPISuite.runTestSuite(List.of(TestUtils::createJFrameWithCustomTitleBar, TestUtils::createJFrameWithCustomTitleBar), hitTestClientAreaSwing);

        boolean status = awtStatus && swingStatus;

        if (!status) {
            throw new RuntimeException("HitTestClientArea FAILED");
        }
    }

    private static final Task hitTestClientAreaAWT = new Task("Hit test client area AWT") {

        private static final List<Integer> BUTTON_MASKS = List.of(
                InputEvent.BUTTON1_DOWN_MASK,
                InputEvent.BUTTON2_DOWN_MASK,
                InputEvent.BUTTON3_DOWN_MASK
        );
        private static final int PANEL_WIDTH = 400;
        private static final int PANEL_HEIGHT = (int) TestUtils.TITLE_BAR_HEIGHT;

        private final int[] gotClicks = new int[BUTTON_MASKS.size()];
        private static boolean mousePressed = false;
        private static boolean mouseReleased = false;

        private Panel panel;

        @Override
        protected void cleanup() {
            Arrays.fill(gotClicks, 0);
            mousePressed = false;
            mouseReleased = false;
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        protected void customizeWindow() {
            panel = new Panel(){
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
                private void hit() {
                    titleBar.forceHitTest(true);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    hit();
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        gotClicks[e.getButton() - 1]++;
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    hit();
                    mousePressed = true;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    hit();
                    mouseReleased = true;
                }
            });

            window.add(panel);
        }

        @Override
        public void test() throws AWTException {
            Robot robot = new Robot();

            int initialX = window.getLocationOnScreen().x + PANEL_WIDTH / 2;
            int initialY = window.getLocationOnScreen().y + PANEL_HEIGHT / 2;

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

            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                System.out.println("Button no " + (i+1) + " clicks count = " + gotClicks[i]);
                if (gotClicks[i] == 0) {
                    System.out.println("Mouse click to button no " + (i+1) + " was not registered");
                    passed = false;
                }
            }

            boolean moved = initialLocation.x < newLocation.x && initialLocation.y < newLocation.y;
            if (moved) {
                System.out.println("Window was moved, but drag'n drop action must be disabled in the client area");
                passed = false;
            }

            if (!mousePressed) {
                System.out.println("Mouse press to the client area wasn't detected");
                passed = false;
            }
            if (!mouseReleased) {
                System.out.println("Mouse release to the client area wasn't detected");
                passed = false;
            }
        }

    };

    private static final Task hitTestClientAreaSwing = new Task("Hit test client area Swing") {
        private static final List<Integer> BUTTON_MASKS = List.of(
                InputEvent.BUTTON1_DOWN_MASK,
                InputEvent.BUTTON2_DOWN_MASK,
                InputEvent.BUTTON3_DOWN_MASK
        );
        private static final int PANEL_WIDTH = 400;
        private static final int PANEL_HEIGHT = (int) TestUtils.TITLE_BAR_HEIGHT;

        private final int[] gotClicks = new int[BUTTON_MASKS.size()];
        private static boolean mousePressed = false;
        private static boolean mouseReleased = false;

        private JPanel panel;

        @Override
        protected void cleanup() {
            Arrays.fill(gotClicks, 0);
            mousePressed = false;
            mouseReleased = false;
            titleBar = null;
        }

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        protected void customizeWindow() {

            panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.CYAN);
                    g.fillRect(r.x, r.y, PANEL_WIDTH, PANEL_HEIGHT);
                }
            };

            if (window.getName().equals("JFrame")) {
                ((JFrame) window).setContentPane(panel);
            } else if (window.getName().equals("JDialog")) {
                ((JDialog) window).setContentPane(panel);
            }

            panel.setBounds(0, 0, PANEL_WIDTH, PANEL_HEIGHT);
            panel.setSize(PANEL_WIDTH, PANEL_HEIGHT);
            panel.addMouseListener(new MouseAdapter() {
                private void hit() {
                    titleBar.forceHitTest(true);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    hit();
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        gotClicks[e.getButton() - 1]++;
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    hit();
                    mousePressed = true;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    hit();
                    mouseReleased = true;
                }
            });
        }

        @Override
        public void test() throws AWTException {
            Robot robot = new Robot();

            int initialX = window.getLocationOnScreen().x + PANEL_WIDTH / 2;
            int initialY = window.getLocationOnScreen().y + PANEL_HEIGHT / 2;

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

            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                System.out.println("Button no " + (i+1) + " clicks count = " + gotClicks[i]);
                if (gotClicks[i] == 0) {
                    System.out.println("Mouse click to button no " + (i+1) + " was not registered");
                    passed = false;
                }
            }

            boolean moved = initialLocation.x < newLocation.x && initialLocation.y < newLocation.y;
            if (moved) {
                System.out.println("Window was moved, but drag'n drop action must be disabled in the client area");
                passed = false;
            }

            if (!mousePressed) {
                System.out.println("Mouse press to the client area wasn't detected");
                passed = false;
            }
            if (!mouseReleased) {
                System.out.println("Mouse release to the client area wasn't detected");
                passed = false;
            }
        }

    };


}