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

import java.awt.AWTException;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JRE-318: Cmd+` doesn't work after update to JDK 152_*
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @run main MoveFocusShortcutTest
 */

/*
 * Description: Test checks that Command+` macOS system shortcut successively switches focus between three Java Frames.
 *
 * Note: Please check that Command+` macOS system shortcut is enabled before launching this test
 * (use System Preferences -> Keyboard -> Shortcuts tab -> Keyboard -> mark 'Move focus to next window' checkbox)
 * On MacOS 10.14 and later accessibility permission should be granted for the application launching this test, so
 * Java Robot is able to access keyboard (use System Preferences -> Security & Privacy -> Privacy tab -> Accessibility).
 */

public class MoveFocusShortcutTest {

    private static final int PAUSE = 2000;

    private static final int framesCount = 3;

    private static TestFrame[] frames;
    private static WindowAdapter frameFocusListener;

    private static Robot robot;

    private static class TestFrame extends Frame {

        private final CountDownLatch frameGainedFocus;

        private TestFrame(String title) {
            super(title);
            frameGainedFocus = new CountDownLatch(2);
        }

        private CountDownLatch getLatch() {
            return frameGainedFocus;
        }
    }

    /*
     * Checks that pressing Command+` successively switches focus between three Java Frames
     */
    public static void main(String[] args) throws AWTException, InterruptedException {

        if (GraphicsEnvironment.isHeadless()) {
            throw new RuntimeException("ERROR: Cannot execute the test in headless environment");
        }

        frames = new TestFrame[framesCount];

        frameFocusListener = new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent windowEvent) {
                Window window = windowEvent.getWindow();
                try {
                    TestFrame frame = (TestFrame) windowEvent.getWindow();
                    frame.getLatch().countDown();
                    System.out.println("Window gained focus: " + frame.getTitle());
                } catch (ClassCastException e) {
                    throw new RuntimeException("Unexpected window: " + window);
                }
            }
        };

        try {
            robot = new Robot();
            robot.setAutoDelay(50);

            System.out.println("Open test frames");
            for(int i = 0; i < framesCount; i++) {
                showFrame(i);
                Thread.sleep(PAUSE);
                robot.waitForIdle();
            }

            String check = "";
            for(int i = 0; i < framesCount; i++) {
                if(frames[i].getLatch().getCount() != 1) {
                    check += check.isEmpty() ? i : (", " + i);
                }
            }
            if (check.isEmpty()) {
                System.out.println("All frames were opened");
            } else {
                throw new RuntimeException("Test ERROR: Cannot focus the TestFrame(s): " + check);
            }

            for(int i = 0; i < framesCount; i++) {
                moveFocusToNextWindow();
                Thread.sleep(PAUSE);
                robot.waitForIdle();
            }

            String result = "";
            for(int i = 0; i < framesCount; i++) {
                if(!frames[i].getLatch().await(PAUSE, TimeUnit.MILLISECONDS)) {
                    result += result.isEmpty() ? i : (", " + i);
                }
            }
            if(result.isEmpty()) {
                System.out.println("Test PASSED");
            } else {
                throw new RuntimeException("Test FAILED: Command+` shortcut cannot move focus to the TestFrame(s): "
                        + result);
            }
        } finally {
            for(int i = 0; i < framesCount; i++) {
                destroyFrame(i);
            }
            /* Waiting for EDT auto-shutdown */
            Thread.sleep(PAUSE);
        }
    }

    /*
     * Opens a test frame
     */
    private static void showFrame(int num) {
        frames[num] = new TestFrame("TestFrame" + num);
        frames[num].setSize(400, 200);
        frames[num].setLocation(50*(num+1), 50*(num+1));
        frames[num].addWindowFocusListener(frameFocusListener);
        frames[num].setVisible(true);
    }

    /*
     * Presses Command+` on macOS keyboard
     */
    private static void moveFocusToNextWindow() {
        System.out.println("Move focus to the next window");
        robot.keyPress(KeyEvent.VK_META);
        robot.keyPress(KeyEvent.VK_BACK_QUOTE);
        robot.keyRelease(KeyEvent.VK_BACK_QUOTE);
        robot.keyRelease(KeyEvent.VK_META);
    }

    /*
     * Disposes a test frame
     */
    private static void destroyFrame(int num) {
        if(frames[num] != null) {
            frames[num].removeWindowFocusListener(frameFocusListener);
            frames[num].dispose();
        }
    }
}
