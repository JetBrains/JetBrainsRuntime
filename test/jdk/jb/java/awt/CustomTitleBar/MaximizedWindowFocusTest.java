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
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar ../../../testhelpers/utils
 * @build TestUtils TaskResult Task CommonAPISuite MouseUtils ScreenShotHelpers Rect RectCoordinates MouseUtils
 * @run main/othervm MaximizedWindowFocusTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 MaximizedWindowFocusTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 MaximizedWindowFocusTest
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