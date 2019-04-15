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
