/*
 * Copyright 2000-2024 JetBrains s.r.o.
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
import test.jb.testhelpers.TitleBar.TestUtils;

import java.awt.Point;
import java.awt.Dimension;
import java.awt.Robot;
import java.awt.event.InputEvent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

/*
 * @test
 * @summary Check an expanded menu in a custom title bar can be closed by clicking it
 * @requires (os.family != "mac")
 * @library ../../../testhelpers/TitleBar
 * @build TestUtils
 * @run main/othervm JMenuClickToCloseTest
 */

public class JMenuClickToCloseTest {
    private static WindowDecorations windowDecorations;
    private static JMenu menu;
    private static JFrame jFrame;
    private static boolean passed = false;
    static volatile Point menuLocation;
    static volatile Dimension menuSize;
    static volatile boolean isMenuVisible;

    public static void main(String... args) throws Exception {
        var robot = new Robot();
        robot.setAutoDelay(50);
        try {
            SwingUtilities.invokeAndWait(JMenuClickToCloseTest::prepareUI);
            if (windowDecorations == null) {
                System.out.println("TEST SKIPPED - custom window decorations aren't supported in this environment");
                return;
            }
            robot.waitForIdle();
            SwingUtilities.invokeAndWait(() -> {
                menuLocation = menu.getLocationOnScreen();
                menuSize = menu.getSize();
            });
            robot.mouseMove(
                    menuLocation.x + menuSize.width / 2,
                    menuLocation.y + menuSize.height / 2
            );
            robot.waitForIdle();
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            robot.delay(1000);
            SwingUtilities.invokeAndWait(() -> {
                isMenuVisible = menu.isPopupMenuVisible();
            });
            if (!isMenuVisible) {
                throw new RuntimeException("Menu was not opened on click");
            }
            robot.waitForIdle();
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            robot.delay(1000);
            SwingUtilities.invokeAndWait(() -> {
                isMenuVisible = menu.isPopupMenuVisible();
            });
            if (isMenuVisible) {
                throw new RuntimeException("Menu was not closed on second click");
            }
        } finally {
            SwingUtilities.invokeAndWait(JMenuClickToCloseTest::disposeUI);
        }
        if (!passed) {
            System.out.println("TEST FAILED");
        } else {
            System.out.println("TEST PASSED");
        }
    }

    private static void prepareUI() {
        windowDecorations = JBR.getWindowDecorations();
        if (windowDecorations == null) return;

        var menuBar = new JMenuBar();
        menu = new JMenu("test");
        var menuItem = new JMenuItem("test");
        menu.add(menuItem);
        menuBar.add(menu);

        var titleBar = windowDecorations.createCustomTitleBar();
        titleBar.setHeight(100.0f);
        jFrame = TestUtils.createJFrameWithCustomTitleBar(titleBar);

        jFrame.setJMenuBar(menuBar);
        jFrame.getRootPane().getLayeredPane().add(menuBar, (Object)(JLayeredPane.DEFAULT_LAYER - 1));

        jFrame.setVisible(true);
        jFrame.requestFocus();
    }

    private static void disposeUI() {
        if (jFrame != null) {
            jFrame.dispose();
        }
    }

}
