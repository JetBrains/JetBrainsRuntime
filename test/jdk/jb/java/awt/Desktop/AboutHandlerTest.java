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

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * @test
 * @key headful
 * @requires (os.family == "mac")
 * @summary Regression test for JBR-1718.
 *          When opening the apps About dialog, "AboutHandler hits" should be written to the console and
 *          NO dialog should appear.
 * @run main/othervm AboutHandlerTest
 */

public class AboutHandlerTest {
    private static final int WAIT_TIME = 1000;

    private static Robot robot;

    private static JFrame myApp = new JFrame("MyApp");
    private static boolean testPassed = false;

    public static void main(String[] args) throws InterruptedException, AWTException {

        robot = new Robot();
        robot.setAutoDelay(50);

        Desktop.getDesktop().setAboutHandler(e -> {
            System.out.println("AboutHandler hits");
            testPassed = true;
        });

        SwingUtilities.invokeLater(() -> {
            doTest();
        });

        // waiting for AboutHandler
        sleep(WAIT_TIME);

        myApp.dispose();

        if (!testPassed)
            throw new RuntimeException("AboutHandler was not hit");
    }

    private static void doTest() {
        myApp.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        myApp.setBounds(10, 10, 100, 100);
        myApp.setVisible(true);

        // move at Apple's menu
        robot.mouseMove(0, 0);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        // move at AboutHandlerTest menu
        robot.keyPress(KeyEvent.VK_RIGHT);
        robot.keyRelease(KeyEvent.VK_RIGHT);

        // move at the "About AboutHandlerTest" menu item
        robot.keyPress(KeyEvent.VK_DOWN);
        robot.keyRelease(KeyEvent.VK_DOWN);

        // hit the "About AboutHandlerTest" menu item
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
    }

    private static void sleep(int millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
