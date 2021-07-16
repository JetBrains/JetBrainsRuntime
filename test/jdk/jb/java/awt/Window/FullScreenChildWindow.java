/*
 * Copyright 2021 JetBrains s.r.o.
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

import com.apple.eawt.Application;
import com.apple.eawt.FullScreenAdapter;
import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.event.FullScreenEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3611 Unexpected workspace switch with dialog in full-screen mode on macOS
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 * @compile MacSpacesUtil.java
 * @run main FullScreenChildWindow
*/

public class FullScreenChildWindow {
    private static final CompletableFuture<Boolean> shownAtFullScreen = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> dialogShown = new CompletableFuture<>();

    private static Robot robot;
    private static JFrame frame2;
    private static JFrame frame1;
    private static JButton button;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(FullScreenChildWindow::initUI);
            shownAtFullScreen.get(5, TimeUnit.SECONDS);
            clickAt(button);
            dialogShown.get(5, TimeUnit.SECONDS);
            MacSpacesUtil.switchToPreviousSpace();
            if (!frame1.isFocused()) {
                throw new RuntimeException("Unexpected state");
            }
        } finally {
            SwingUtilities.invokeAndWait(FullScreenChildWindow::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("FullScreenChildWindow(1)");
        frame1.setSize(100, 100);
        frame1.setLocation(100, 100);
        frame1.setVisible(true);

        frame2 = new JFrame("FullScreenChildWindow(2)");
        button = new JButton("Open dialog");
        button.addActionListener(e -> {
            JDialog d = new JDialog(frame2, "dialog", false);
            d.setSize(100, 100);
            d.setLocationRelativeTo(null);
            d.setAutoRequestFocus(false);
            d.addWindowListener(new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    dialogShown.complete(true);
                }
            });
            d.setVisible(true);
        });
        frame2.add(button);
        frame2.setSize(100, 100);
        frame2.setLocation(100, 300);
        frame2.setVisible(true);
        FullScreenUtilities.addFullScreenListenerTo(frame2, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                shownAtFullScreen.complete(true);
            }
        });
        Application.getApplication().requestToggleFullScreen(frame2);
    }

    private static void disposeUI() {
        if (frame2 != null) frame2.dispose();
        if (frame1 != null) frame1.dispose();
    }

    private static void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
