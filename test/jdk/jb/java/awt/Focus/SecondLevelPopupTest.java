/*
 * Copyright 2021 JetBrains s.r.o.
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
 * @summary Regression test for JBR-3054 Focus is not returned to frame after closing of second-level popup on Windows
 * @key headful
 */

public class SecondLevelPopupTest {
    private static CompletableFuture<Boolean> button1Focused;
    private static Robot robot;
    private static JFrame frame;
    private static JButton button1;
    private static JButton button2;
    private static JButton button3;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(SecondLevelPopupTest::initUI);
            robot.delay(2000);
            clickOn(button1);
            robot.delay(2000);
            clickOn(button2);
            robot.delay(2000);
            SwingUtilities.invokeAndWait(() -> button1Focused = new CompletableFuture<>());
            clickOn(button3);
            button1Focused.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(SecondLevelPopupTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("SecondLevelPopupTest");
        button1 = new JButton("Open popup");
        button1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (button1Focused != null) button1Focused.complete(true);
            }
        });
        button1.addActionListener(e -> {
            JWindow w = new JWindow(frame);
            w.setFocusableWindowState(false);
            button2 = new JButton("Open another popup");
            button2.addActionListener(ee -> {
                JWindow ww = new JWindow(w);
                button3 = new JButton("Close");
                button3.addActionListener(eee -> {
                    ww.dispose();
                });
                ww.add(button3);
                ww.pack();
                ww.setLocation(200, 400);
                ww.setVisible(true);
            });
            w.add(button2);
            w.pack();
            w.setLocation(200, 300);
            w.setVisible(true);
        });
        frame.add(button1);
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
