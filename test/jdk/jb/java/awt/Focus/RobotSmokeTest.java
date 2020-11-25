/*
 * Copyright 2020 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.*;

/**
 * @test
 * @summary Test validating basic java.awt.Robot functionality (mouse clicks and key presses)
 * @key headful
 */

public class RobotSmokeTest {
    private static final CompletableFuture<Boolean> mouseClicked = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> keyTyped = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JTextField field;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(RobotSmokeTest::initUI);
            robot.delay(5000);
            clickOn(field);
            mouseClicked.get(10, TimeUnit.SECONDS);
            pressAndRelease(KeyEvent.VK_A);
            keyTyped.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(RobotSmokeTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("RobotSmokeTest");
        field = new JTextField(20);
        field.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                mouseClicked.complete(Boolean.TRUE);
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                keyTyped.complete(Boolean.TRUE);
            }
        });
        frame.add(field);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
