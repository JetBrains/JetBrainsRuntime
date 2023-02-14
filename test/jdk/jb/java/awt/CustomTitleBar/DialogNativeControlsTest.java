/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
import com.jetbrains.JBR;
import util.Rect;
import util.ScreenShotHelpers;
import util.Task;
import util.TestUtils;

import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.event.WindowStateListener;
import java.awt.image.BufferedImage;
import java.util.List;

/*
 * @test
 * @summary Detect and check behavior of clicking to native controls
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main DialogNativeControlsTest
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5
 * @run main DialogNativeControlsTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0
 */
public class DialogNativeControlsTest {

    public static void main(String... args) {
        boolean status = nativeControlClicks.run(TestUtils::createDialogWithCustomTitleBar);

        if (!status) {
            throw new RuntimeException("DialogNativeControlsTest FAILED");
        }
    }

    private static final Task nativeControlClicks = new Task("Native controls clicks") {
        private boolean closingActionCalled;
        private boolean maximizingActionDetected;

        private final WindowListener windowListener = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closingActionCalled = true;
            }

        };

        private final WindowStateListener windowStateListener = new WindowAdapter() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                if (e.getNewState() == 6) {
                    maximizingActionDetected = true;
                }
            }
        };

        @Override
        public void prepareTitleBar() {
            titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        }

        @Override
        protected void init() {
            closingActionCalled = false;
            maximizingActionDetected = false;
        }

        @Override
        protected void cleanup() {
            window.removeWindowListener(windowListener);
            window.removeWindowStateListener(windowStateListener);
        }

        @Override
        protected void customizeWindow() {
            window.addWindowListener(windowListener);
            window.addWindowStateListener(windowStateListener);
        }

        @Override
        public void test() throws Exception {
            robot.delay(500);
            robot.mouseMove(window.getLocationOnScreen().x + window.getWidth() / 2,
                    window.getLocationOnScreen().y + window.getHeight() / 2);
            robot.delay(500);

            BufferedImage image = ScreenShotHelpers.takeScreenshot(window);
            List<Rect> foundControls = ScreenShotHelpers.detectControlsByBackground(image, (int) titleBar.getHeight(), TestUtils.TITLE_BAR_COLOR);

            if (foundControls.size() == 0) {
                passed = false;
                System.out.println("Error: no controls found");
            }

            foundControls.forEach(control -> {
                System.out.println("Using control: " + control);
                int x = window.getLocationOnScreen().x + control.getX1() + (control.getX2() - control.getX1()) / 2;
                int y = window.getLocationOnScreen().y + control.getY1() + (control.getY2() - control.getY1()) / 2;
                System.out.println("Click to (" + x + ", " + y + ")");

                int screenX = window.getBounds().x;
                int screenY = window.getBounds().y;
                int h = window.getBounds().height;
                int w = window.getBounds().width;

                robot.delay(500);
                robot.mouseMove(x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.delay(1500);
                window.setBounds(screenX, screenY, w, h);
                window.setVisible(true);
                robot.delay(1500);
            });

            if (System.getProperty("os.name").toLowerCase().startsWith("mac") && !maximizingActionDetected) {
                passed = false;
                System.out.println("Error: maximizing action was not detected");
            }

            if (!closingActionCalled) {
                passed = false;
                System.out.println("Error: closing action was not detected");
            }

        }
    };

}
