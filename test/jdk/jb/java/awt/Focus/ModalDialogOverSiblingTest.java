/*
 * Copyright 2000-2021 JetBrains s.r.o.
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
 * @summary Regression test for JBR-1752 Floating windows overlap modal dialogs
 * @key headful
 */

public class ModalDialogOverSiblingTest {
    private static final CompletableFuture<Boolean> modalDialogButtonClicked = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(ModalDialogOverSiblingTest::initUI);
            robot.delay(2000);
            clickOn(button1);
            robot.delay(2000);
            clickOn(button2);
            robot.delay(2000);
            clickOn(button3);
            modalDialogButtonClicked.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(ModalDialogOverSiblingTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ModalDialogOverSiblingTest");
        frame.setLayout(new FlowLayout());
        button1 = new JButton("Open non-modal dialog");
        button1.addActionListener(e -> {
            JDialog dialog = new JDialog(frame, "Non-modal", false);
            dialog.setSize(200, 200);
            dialog.setLocation(200, 400);
            dialog.setVisible(true);
        });
        frame.add(button1);
        button2 = new JButton("Open modal dialog");
        button2.addActionListener(e -> {
            JDialog dialog = new JDialog(frame, "Modal", true);
            button3 = new JButton("Button");
            button3.addActionListener(event -> modalDialogButtonClicked.complete(true));
            dialog.add(button3);
            dialog.setSize(150, 150);
            dialog.setLocation(220, 420);
            dialog.setVisible(true);
        });
        frame.add(button2);
        frame.pack();
        frame.setLocation(200, 200);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
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
