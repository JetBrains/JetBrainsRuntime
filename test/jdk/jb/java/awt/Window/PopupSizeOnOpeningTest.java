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

/**
 * @test
 * @summary Regression test for JBR-3024 Popups are shown with 1x1 size sometimes
 * @key headful
 */

public class PopupSizeOnOpeningTest {
    private static Robot robot;
    private static JFrame frame;
    private static JLabel clickPlace;
    private static JWindow popup;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(PopupSizeOnOpeningTest::initUI);
            robot.delay(3000);
            clickAt(clickPlace); // make sure frame is focused

            for (int i = 0; i < 10; i++) {
                robot.delay(1000);
                pressAndReleaseSpace(); // open popup
                robot.delay(1000);
                Dimension size = popup.getSize();
                if (size.width <= 1 || size.height <= 1) {
                    throw new RuntimeException("Unexpected popup size: " + size);
                }
                pressAndReleaseSpace(); // close popup
            }
        } finally {
            SwingUtilities.invokeAndWait(PopupSizeOnOpeningTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("PopupSizeOnOpeningTest");
        frame.setLayout(new FlowLayout());
        frame.add(clickPlace = new JLabel("Click here"));
        JButton openButton = new JButton("Open popup");
        openButton.addActionListener(e -> {
            popup = new JWindow(frame);
            JButton closeButton = new JButton("Close popup");
            closeButton.addActionListener(ev -> {
                popup.dispose();
                popup = null;
            });
            popup.add(closeButton);
            popup.pack();
            popup.setLocationRelativeTo(frame);
            popup.setVisible(true);
        });
        frame.add(openButton);
        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void pressAndReleaseSpace() {
        robot.keyPress(KeyEvent.VK_SPACE);
        robot.keyRelease(KeyEvent.VK_SPACE);
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
