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
