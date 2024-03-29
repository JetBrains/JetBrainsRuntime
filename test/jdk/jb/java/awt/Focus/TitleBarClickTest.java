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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2652 Window is not focused in some cases on Linux
 * @key headful
 */
public class TitleBarClickTest {
    private static final CompletableFuture<Boolean> f1Opened = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> f2Opened = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> t2Clicked = new CompletableFuture<>();
    private static CompletableFuture<Boolean> t1Focused;
    private static CompletableFuture<Boolean> t2Focused;

    private static JFrame f1;
    private static JFrame f2;
    private static JTextField t1;
    private static JTextField t2;
    private static Robot robot;

    public static void main(String[] args) throws Exception {
        robot = new Robot();

        try {
            SwingUtilities.invokeAndWait(TitleBarClickTest::initFrame1);
            f1Opened.get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(TitleBarClickTest::initFrame2);
            f2Opened.get(10, TimeUnit.SECONDS);
            clickAt(t2);
            t2Clicked.get(10, TimeUnit.SECONDS);

            SwingUtilities.invokeAndWait(() -> t1Focused = new CompletableFuture<>());
            clickAtTitle(f1);
            t1Focused.get(10, TimeUnit.SECONDS);

            SwingUtilities.invokeAndWait(() -> t2Focused = new CompletableFuture<>());
            robot.delay(1000); // give WM a bit more time
            f2.toFront();
            t2Focused.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(TitleBarClickTest::disposeUI);
        }
    }

    private static void initFrame1() {
        f1 = new JFrame("first");
        f1.add(t1 = new JTextField());
        t1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (t1Focused != null) t1Focused.complete(Boolean.TRUE);
            }
        });
        f1.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                f1Opened.complete(Boolean.TRUE);
            }
        });
        f1.setBounds(100, 100, 300, 100);
        f1.setVisible(true);
    }

   private static void initFrame2() {
        f2 = new JFrame("second");
        f2.add(t2 = new JTextField());
        t2.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (t2Focused != null) t2Focused.complete(Boolean.TRUE);
            }
        });
        t2.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                t2Clicked.complete(Boolean.TRUE);
            }
        });
        f2.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                f2Opened.complete(Boolean.TRUE);
            }
        });
        f2.setBounds(450, 100, 300, 100);
        f2.setVisible(true);
    }

    private static void disposeUI() {
        if (f1 != null) f1.dispose();
        if (f2 != null) f2.dispose();
    }

    private static void clickAt(int x, int y) {
        robot.delay(1000); // needed for GNOME, to give it some time to update internal state after window showing
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickAt(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }

    private static void clickAtTitle(Window window) {
        int topInset = window.getInsets().top;
        if (topInset <= 0) throw new RuntimeException("Window doesn't have title bar");
        Point location = window.getLocationOnScreen();
        clickAt(location.x + window.getWidth() / 2, location.y + topInset / 2);
    }
}
