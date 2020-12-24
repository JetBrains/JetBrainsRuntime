/*
 * Copyright 2000-2020 JetBrains s.r.o.
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2977 Opening a recent project in a new window doesn't bring this window to the front
 * @key headful
 */

public class NewFrameAfterDialogTest {
    private static final CompletableFuture<Boolean> success = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JDialog dialog;
    private static JFrame frame2;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(NewFrameAfterDialogTest::initUI);
            robot.delay(3000); // wait for the frame to appear
            clickOn(frame);
            robot.delay(3000); // wait for dialog to appear
            clickOn(dialog);
            robot.delay(3000); // wait for second frame to appear
            clickOn(frame2);
            success.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(NewFrameAfterDialogTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("NewFrameAfterDialogTest");
        JButton button = new JButton("Open another frame");
        button.addActionListener(e -> {
            dialog = new JDialog(frame, true);
            JButton b = new JButton("Confirm");
            b.addActionListener(e2 -> dialog.dispose());
            dialog.add(b);
            dialog.setSize(300, 300);
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            frame2 = new JFrame("Second frame");
            JButton b2 = new JButton("Close");
            b2.addActionListener(e3 -> success.complete(true));
            frame2.add(b2);
            frame2.setSize(300, 300);
            frame2.setLocationRelativeTo(null);
            frame2.setVisible(true);
        });
        frame.add(button);
        frame.setSize(300, 300);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
        if (frame2 != null) frame2.dispose();
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
