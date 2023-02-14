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
import com.jetbrains.WindowDecorations;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/*
 * @test
 * @summary Regression test for JBR-3157 Maximized window with custom decorations isn't focused on showing
 * @requires (os.family == "windows" | os.family == "mac")
 * @run main/othervm MaximizedWindowFocusTest
 * @run main/othervm MaximizedWindowFocusTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0
 * @run main/othervm MaximizedWindowFocusTest -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0
 */
public class MaximizedWindowFocusTest {

    private static Robot robot;
    private static JFrame frame1;
    private static JFrame frame2;
    private static JButton button;
    private static final CompletableFuture<Boolean> frame2Focused = new CompletableFuture<>();

    public static void main(String... args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(MaximizedWindowFocusTest::initUI);
            robot.delay(1000);
            clickOn(button);
            frame2Focused.get(5, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(MaximizedWindowFocusTest::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("MaximizedCustomDecorationsTest");
        button = new JButton("Open maximized frame");
        button.addActionListener(e -> {
            frame1.dispose();
            frame2 = new JFrame("Frame with custom decorations");
            WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(100);
            JBR.getWindowDecorations().setCustomTitleBar(frame2, titleBar);

            frame2.addWindowFocusListener(new WindowAdapter() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                    frame2Focused.complete(true);
                }
            });
            frame2.setExtendedState(Frame.MAXIMIZED_BOTH);
            frame2.setVisible(true);
        });
        frame1.add(button);
        frame1.pack();
        frame1.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
    }

    private static void clickAt(int x, int y) {
        robot.delay(500);
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(500);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
