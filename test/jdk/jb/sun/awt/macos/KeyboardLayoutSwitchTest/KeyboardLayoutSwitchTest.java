/*
 * Copyright 2000-2019 JetBrains s.r.o.
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
import java.awt.event.KeyEvent;

public class KeyboardLayoutSwitchTest implements Runnable {

    private static Robot robot;
    private JFrame frame;

    public static void main(String[] args) throws Exception {

        System.out.println("VK_TAB = " + KeyEvent.VK_TAB);
        robot = new Robot();
        robot.setAutoDelay(50);

        KeyboardLayoutSwitchTest test = new KeyboardLayoutSwitchTest();
        SwingUtilities.invokeAndWait(test);
        robot.delay(3000);

        try {
            SwingUtilities.invokeAndWait(() ->
                    pressCtrlKey(KeyEvent.VK_TAB));
        } finally {
            SwingUtilities.invokeAndWait(() -> test.frame.dispose());
            robot.delay(2000);
        }
        System.out.println("Test PASSED");
    }

    private static void pressCtrlKey(int vk) {
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(vk);
        robot.keyRelease(vk);
        robot.keyRelease(KeyEvent.VK_CONTROL);
    }

    @Override
    public void run() {
        frame = new JFrame(getClass().getSimpleName());
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
    }
}