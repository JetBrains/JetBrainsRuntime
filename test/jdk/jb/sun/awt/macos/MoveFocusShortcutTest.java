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

    private static TestFrame frame1;
    private static TestFrame frame2;
    private static TestFrame frame3;

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

        frame1 = new TestFrame("TestFrame1");
        frame2 = new TestFrame("TestFrame2");
        frame3 = new TestFrame("TestFrame3");

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
            showGUI();

            Thread.sleep(PAUSE);
            robot.waitForIdle();

            boolean check1 = (frame1.getLatch().getCount() == 1);
            boolean check2 = (frame2.getLatch().getCount() == 1);
            boolean check3 = (frame3.getLatch().getCount() == 1);

            if (check1 && check2 && check3) {
                System.out.println("All frames were opened");
            } else {
                throw new RuntimeException("Test ERROR: Cannot focus the TestFrame(s): "
                        + getFailedChecksString(check1, check2, check3));
            }

            moveFocusToNextWindow();
            Thread.sleep(PAUSE);
            robot.waitForIdle();

            moveFocusToNextWindow();
            Thread.sleep(PAUSE);
            robot.waitForIdle();

            moveFocusToNextWindow();
            Thread.sleep(PAUSE);
            robot.waitForIdle();

            boolean result1 = frame1.getLatch().await(PAUSE, TimeUnit.MILLISECONDS);
            boolean result2 = frame2.getLatch().await(PAUSE, TimeUnit.MILLISECONDS);
            boolean result3 = frame3.getLatch().await(PAUSE, TimeUnit.MILLISECONDS);

            if(result1 && result2 && result3) {
                System.out.println("Test PASSED");
            } else {
                throw new RuntimeException("Test FAILED: Command+` shortcut cannot move focus to the TestFrame(s): "
                        + getFailedChecksString(result1 , result2 , result3));
            }
        } finally {
            destroyGUI();
            /* Waiting for EDT auto-shutdown */
            Thread.sleep(PAUSE);
        }
    }

    /*
     * Opens test frames
     */
    private static void showGUI() {
        frame1.setSize(400, 200);
        frame2.setSize(400, 200);
        frame3.setSize(400, 200);
        frame1.setLocation(50, 50);
        frame2.setLocation(100, 100);
        frame3.setLocation(150, 150);
        frame1.addWindowFocusListener(frameFocusListener);
        frame2.addWindowFocusListener(frameFocusListener);
        frame3.addWindowFocusListener(frameFocusListener);
        frame1.setVisible(true);
        frame2.setVisible(true);
        frame3.setVisible(true);
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
     * Returns string containing positions of the false values
     */
    private static String getFailedChecksString(boolean ... values) {
        int i = 0;
        String result = "";
        for (boolean value : values) {
            i++;
            if(!value) {
                result += result.isEmpty() ? i : (", " + i);
            }
        }
        return result;
    }

    /*
     * Disposes test frames
     */
    private static void destroyGUI() {
        frame1.removeWindowFocusListener(frameFocusListener);
        frame2.removeWindowFocusListener(frameFocusListener);
        frame3.removeWindowFocusListener(frameFocusListener);
        frame1.dispose();
        frame2.dispose();
        frame3.dispose();
    }
}
