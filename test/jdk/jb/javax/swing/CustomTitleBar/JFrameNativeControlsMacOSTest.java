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
import com.apple.eawt.FullScreenListener;
import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.event.FullScreenEvent;
import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;
import test.jb.testhelpers.TitleBar.TestUtils;
import test.jb.testhelpers.screenshot.ScreenShotHelpers;
import test.jb.testhelpers.screenshot.Rect;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Robot;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;

/*
 * @test
 * @summary Detect and check behavior of clicking to native controls
 * @requires (os.family == "mac")
 * @library ../../../testhelpers/screenshot ../../../testhelpers/TitleBar
 * @build TestUtils ScreenShotHelpers Rect RectCoordinates
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 * @run main/othervm JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.5 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.0 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=3.5 JFrameNativeControlsMacOSTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=4.0 JFrameNativeControlsMacOSTest
 */
public class JFrameNativeControlsMacOSTest {

    private static WindowDecorations.CustomTitleBar titleBar;
    private static JFrame jFrame;
    private static WindowListener windowListener;
    private static WindowStateListener windowStateListener;
    private static FullScreenListener fullScreenListener;

    private static boolean closingActionCalled = false;
    private static boolean iconifyingActionCalled = false;
    private static volatile boolean maximizingActionDetected = false;
    private static boolean deiconifyindActionDetected = false;
    private static boolean passed = true;
    private static String error = "";

    private static Robot robot;

    public static void main(String... args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(JFrameNativeControlsMacOSTest::prepareUI);

            robot.delay(500);
            robot.mouseMove(jFrame.getLocationOnScreen().x + jFrame.getWidth() / 2,
                    jFrame.getLocationOnScreen().y + jFrame.getHeight() / 2);
            robot.delay(500);

            BufferedImage image = ScreenShotHelpers.takeScreenshot(jFrame);
            List<Rect> foundControls = ScreenShotHelpers.findControls(image, jFrame, titleBar);

            if (foundControls.isEmpty()) {
                System.out.println("Error: no controls found");
            }
            foundControls.forEach(control -> {
                System.out.println("Using control: " + control);
                int x = jFrame.getLocationOnScreen().x + control.getX1() + (control.getX2() - control.getX1()) / 2;
                int y = jFrame.getLocationOnScreen().y + control.getY1() + (control.getY2() - control.getY1()) / 2;
                System.out.println("Click to (" + x + ", " + y + ")");

                int screenX = jFrame.getBounds().x;
                int screenY = jFrame.getBounds().y;
                int h = jFrame.getBounds().height;
                int w = jFrame.getBounds().width;

                robot.waitForIdle();
                robot.mouseMove(x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.waitForIdle();
                if (maximizingActionDetected) {
                    robot.keyPress(KeyEvent.VK_META);
                    robot.keyPress(KeyEvent.VK_CONTROL);
                    robot.keyPress(KeyEvent.VK_F);
                    robot.keyRelease(KeyEvent.VK_META);
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                    robot.keyRelease(KeyEvent.VK_F);
                    robot.delay(500);
                }
                jFrame.setBounds(screenX, screenY, w, h);
                jFrame.setVisible(true);
                robot.waitForIdle();
            });
        } finally {
            SwingUtilities.invokeAndWait(JFrameNativeControlsMacOSTest::disposeUI);
        }

        if (!maximizingActionDetected) {
            err("maximizing action was not detected");
        }

        if (!closingActionCalled) {
            err("closing action was not detected");
        }

        if (!iconifyingActionCalled) {
            err("iconifying action was not detected");
        }
        if (!deiconifyindActionDetected) {
            err("deiconifying action was not detected");
        }
        if (!passed) {
            System.out.println("TEST FAILED");
        } else {
            System.out.println("TEST PASSED");
        }
    }

    private static void prepareUI() {
        titleBar = JBR.getWindowDecorations().createCustomTitleBar();
        titleBar.setHeight(TestUtils.TITLE_BAR_HEIGHT);
        jFrame = TestUtils.createJFrameWithCustomTitleBar(titleBar);

        addWindowListener();
        addWindowStateListener();
        addMacOsFullScreenListener();
        jFrame.setVisible(true);
        jFrame.setAlwaysOnTop(true);
        jFrame.requestFocus();
    }

    private static void disposeUI() {
        jFrame.removeWindowListener(windowListener);
        jFrame.removeWindowStateListener(windowStateListener);
        FullScreenUtilities.removeFullScreenListenerFrom(jFrame, fullScreenListener);
        if (jFrame != null) {
            jFrame.dispose();
        }
    }

    private static void addWindowListener() {
        windowListener = new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closingActionCalled = true;
            }

            @Override
            public void windowIconified(WindowEvent e) {
                iconifyingActionCalled = true;

                jFrame.setState(JFrame.NORMAL);
                jFrame.setVisible(true);
                jFrame.requestFocus();
            }
        };
        jFrame.addWindowListener(windowListener);
    }

    private static void addWindowStateListener() {
        windowStateListener = new WindowAdapter() {
            @Override
            public void windowStateChanged(WindowEvent e) {
                System.out.println("change " + e.getOldState() + " -> " + e.getNewState());
                if (e.getNewState() == 6) {
                    maximizingActionDetected = true;
                }
                if (e.getOldState() == 1 && e.getNewState() == 0) {
                    deiconifyindActionDetected = true;
                }
            }
        };
        jFrame.addWindowStateListener(windowStateListener);
    }

    private static void addMacOsFullScreenListener() {
        fullScreenListener = new FullScreenListener() {
            @Override
            public void windowEnteringFullScreen(FullScreenEvent fse) {
                maximizingActionDetected = true;
            }

            @Override
            public void windowEnteredFullScreen(FullScreenEvent fse) {
            }

            @Override
            public void windowExitingFullScreen(FullScreenEvent fse) {
                System.out.println("Exiting fullscreen");
            }

            @Override
            public void windowExitedFullScreen(FullScreenEvent fse) {
                System.out.println("Exited fullscreen");
            }
        };
        FullScreenUtilities.addFullScreenListenerTo(jFrame, fullScreenListener);
    }

    private static void err(String message) {
        error = error + message + "\n";
        passed = false;
        System.out.println(message);
    }

}
