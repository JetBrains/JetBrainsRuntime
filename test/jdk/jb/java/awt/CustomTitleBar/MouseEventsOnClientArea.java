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
import com.jetbrains.WindowDecorations;
import util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/*
 * @test
 * @summary Verify mouse events on custom title bar area
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 MouseEventsOnClientArea
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 MouseEventsOnClientArea
 */
public class MouseEventsOnClientArea {

    private static final List<Integer> BUTTON_MASKS = List.of(
            InputEvent.BUTTON1_DOWN_MASK,
            InputEvent.BUTTON2_DOWN_MASK,
            InputEvent.BUTTON3_DOWN_MASK
    );

    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = (int) TestUtils.TITLE_BAR_HEIGHT;

    public static void main(String... args) {
        TaskResult AWTResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createFrameWithCustomTitleBar, TestUtils::createDialogWithCustomTitleBar), mouseClicksTestAWT);
        TaskResult SwingResult = CommonAPISuite.runTestSuite(List.of(TestUtils::createJFrameWithCustomTitleBar, TestUtils::createJDialogWithCustomTitleBar), mouseClicksTestSwing);

        TaskResult result = AWTResult.merge(SwingResult);

        if (!result.isPassed()) {
            final String message = String.format("%s FAILED. %s", MethodHandles.lookup().lookupClass().getName(), result.getError());
            throw new RuntimeException(message);
        }
    }

    private static final Task mouseClicksTestAWT = new AWTTask("mouseCliensAWT") {

        private final boolean[] buttonsPressed = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsReleased = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsClicked = new boolean[BUTTON_MASKS.size()];

        private boolean contentPressed = false;
        private boolean contentReleased = false;
        private boolean contentClicked = false;

        private final CountDownLatch latch = new CountDownLatch(12);
        private Panel titlePanel;
        private Panel contentPanel;

        @Override
        protected void init() {
            Arrays.fill(buttonsPressed, false);
            Arrays.fill(buttonsReleased, false);
            Arrays.fill(buttonsClicked, false);
        }

        @Override
        public void prepareTitleBar() {
            titleBar = createTitleBar();
        }

        @Override
        protected void customizeWindow() {
            titlePanel = new Panel() {
                @Override
                public void paint(Graphics g) {
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.CYAN);
                    g.fillRect(r.x, r.y, PANEL_WIDTH, PANEL_HEIGHT);
                    super.paint(g);
                }
            };
            final int effectiveWidth = window.getWidth() - window.getInsets().left - window.getInsets().right;
            titlePanel.setPreferredSize(new Dimension(effectiveWidth, (int) TestUtils.TITLE_BAR_HEIGHT));
            titlePanel.setBounds(0, 0, effectiveWidth, (int) TestUtils.TITLE_BAR_HEIGHT);
            titlePanel.addMouseListener(new MouseAdapter() {

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        if (!buttonsClicked[e.getButton() - 1]) {
                            buttonsClicked[e.getButton() - 1] = true;
                            latch.countDown();
                        }
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        if (!buttonsPressed[e.getButton() - 1]) {
                            buttonsPressed[e.getButton() - 1] = true;
                            latch.countDown();
                        }
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() >= 1 && e.getButton() <= 3) {
                        if (!buttonsReleased[e.getButton() - 1]) {
                            buttonsReleased[e.getButton() - 1] = true;
                            latch.countDown();
                        }
                    }
                }
            });

            final int contentHeight = window.getHeight() - window.getInsets().top - window.getInsets().bottom - (int) TestUtils.TITLE_BAR_HEIGHT;
            contentPanel = new Panel() {
                @Override
                public void paint(Graphics g) {
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.GREEN);
                    g.fillRect(r.x, r.y, effectiveWidth, contentHeight);
                }
            };
            contentPanel.setPreferredSize(new Dimension(effectiveWidth, contentHeight));
            contentPanel.setBounds(0, (int) TestUtils.TITLE_BAR_HEIGHT, effectiveWidth, contentHeight);
            contentPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!contentClicked) {
                        contentClicked = true;
                        latch.countDown();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (!contentPressed) {
                        contentPressed = true;
                        latch.countDown();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!contentReleased) {
                        contentReleased = true;
                        latch.countDown();
                    }
                }
            });

            window.add(titlePanel);
            window.add(contentPanel);
            window.setAlwaysOnTop(true);
        }

        @Override
        public void test() throws AWTException, InterruptedException {
            Robot robot = new Robot();

            int x = titlePanel.getLocationOnScreen().x + titlePanel.getWidth() / 2;
            int y = titlePanel.getLocationOnScreen().y + titlePanel.getHeight() / 2;

            BUTTON_MASKS.forEach(mask -> {
                robot.delay(500);

                robot.mouseMove(x, y);
                robot.mousePress(mask);
                robot.mouseRelease(mask);

                robot.delay(500);
            });

            int centerX = contentPanel.getLocationOnScreen().x + contentPanel.getWidth() / 2;
            int centerY = contentPanel.getLocationOnScreen().y + contentPanel.getHeight() / 2;
            robot.delay(500);
            robot.mouseMove(centerX, centerY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();

            boolean result = latch.await(3, TimeUnit.SECONDS);
            System.out.println("result = " + result);

            boolean status = true;
            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                System.out.println("Button mask: " + BUTTON_MASKS.get(i));
                System.out.println("pressed = " + buttonsPressed[i]);
                System.out.println("released = " + buttonsReleased[i]);
                System.out.println("clicked = " + buttonsClicked[i]);
                status = status && buttonsPressed[i] && buttonsReleased[i] && buttonsClicked[i];
            }
            if (!status) {
                err("some of mouse events weren't registered");
            }
        }
    };

    private static final Task mouseClicksTestSwing = new SwingTask("mouseClicksSwing") {

        private final boolean[] buttonsPressed = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsReleased = new boolean[BUTTON_MASKS.size()];
        private final boolean[] buttonsClicked = new boolean[BUTTON_MASKS.size()];

        private boolean contentPressed = false;
        private boolean contentReleased = false;
        private boolean contentClicked = false;

        private final CountDownLatch latch = new CountDownLatch(12);

        private JPanel titlePanel;
        private JPanel contentPanel;

        @Override
        protected void init() {
            Arrays.fill(buttonsPressed, false);
            Arrays.fill(buttonsReleased, false);
            Arrays.fill(buttonsClicked, false);
        }

        @Override
        public void prepareTitleBar() {
            titleBar = createTitleBar();
        }

        @Override
        protected void customizeWindow() {
            titlePanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.CYAN);
                    g.fillRect(r.x, r.y, PANEL_WIDTH, PANEL_HEIGHT);
                }
            };
            final int effectiveWidth = window.getWidth() - window.getInsets().left - window.getInsets().right;
            titlePanel.setPreferredSize(new Dimension(effectiveWidth, (int) TestUtils.TITLE_BAR_HEIGHT));
            titlePanel.setBounds(0, 0, effectiveWidth, (int) TestUtils.TITLE_BAR_HEIGHT);
            titlePanel.addMouseListener(new MouseAdapter() {

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
            });

            final int contentHeight = window.getHeight() - window.getInsets().top - window.getInsets().bottom - (int) TestUtils.TITLE_BAR_HEIGHT;
            contentPanel = new JPanel() {

                @Override
                protected void paintComponent(Graphics g) {
                    Rectangle r = g.getClipBounds();
                    g.setColor(Color.GREEN);
                    g.fillRect(r.x, r.y, effectiveWidth, contentHeight);
                }
            };
            contentPanel.setPreferredSize(new Dimension(effectiveWidth, contentHeight));
            contentPanel.setBounds(0, (int) TestUtils.TITLE_BAR_HEIGHT, effectiveWidth, contentHeight);
            contentPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!contentClicked) {
                        contentClicked = true;
                        latch.countDown();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (!contentPressed) {
                        contentPressed = true;
                        latch.countDown();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!contentReleased) {
                        contentReleased = true;
                        latch.countDown();
                    }
                }
            });

            window.add(titlePanel);
            window.add(contentPanel);
            window.setAlwaysOnTop(true);
        }

        @Override
        public void test() throws Exception {
            Robot robot = new Robot();

            int x = titlePanel.getLocationOnScreen().x + titlePanel.getWidth() / 2;
            int y = titlePanel.getLocationOnScreen().y + titlePanel.getHeight() / 2;

            BUTTON_MASKS.forEach(mask -> {
                robot.delay(500);

                robot.mouseMove(x, y);
                robot.mousePress(mask);
                robot.mouseRelease(mask);

                robot.delay(500);
            });

            int centerX = contentPanel.getLocationOnScreen().x + contentPanel.getWidth() / 2;
            int centerY = contentPanel.getLocationOnScreen().y + contentPanel.getHeight() / 2;
            robot.delay(500);
            robot.mouseMove(centerX, centerY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();

            boolean result = latch.await(3, TimeUnit.SECONDS);
            System.out.println("result = " + result);

            boolean status = true;
            for (int i = 0; i < BUTTON_MASKS.size(); i++) {
                System.out.println("Button mask: " + BUTTON_MASKS.get(i));
                System.out.println("pressed = " + buttonsPressed[i]);
                System.out.println("released = " + buttonsReleased[i]);
                System.out.println("clicked = " + buttonsClicked[i]);
                status = status && buttonsPressed[i] && buttonsReleased[i] && buttonsClicked[i];
            }
            if (!status) {
                err("some of mouse events weren't registered");
            }
        }
    };

    private static WindowDecorations.CustomTitleBar createTitleBar() {
        WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
        titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        return titleBar;
    }

}