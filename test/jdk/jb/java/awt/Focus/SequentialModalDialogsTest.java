/*
 * Copyright 2000-2020 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2843 No caret in merge window
 * @key headful
 */

public class SequentialModalDialogsTest {
    private static final CompletableFuture<Boolean> initFinished = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> secondDialogShown = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> typedInDialog = new CompletableFuture<>();
    private static Robot robot;
    private static JFrame frame;
    private static JButton frameButton;
    private static JTextField windowField;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(SequentialModalDialogsTest::initUI);
            initFinished.get(10, TimeUnit.SECONDS);
            clickOn(frameButton);
            secondDialogShown.get(10, TimeUnit.SECONDS);
            pressAndRelease(KeyEvent.VK_ENTER);
            typedInDialog.get(10, TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(SequentialModalDialogsTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("SequentialModalDialogsTest");
        frameButton = new JButton("Open dialogs");
        frameButton.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                initFinished.complete(true);
            }
        });
        frameButton.addActionListener(e -> {
            showDialogs();
        });
        frame.add(frameButton);
        frame.pack();
        frame.setVisible(true);
    }

    private static void showDialogs() {
        JDialog d1 = new JDialog(frame, true);
        JTextField t1 = new JTextField("dialog 1");
        t1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // using invokeLater to emulate some moment after dialog showing
                SwingUtilities.invokeLater(() -> d1.setVisible(false));
            }
        });
        d1.add(t1);
        d1.pack();
        d1.setVisible(true);
        JDialog d2 = new JDialog(frame, true);
        JTextField t2 = new JTextField("dialog 2");
        t2.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // using invokeLater to emulate some moment after dialog showing
                SwingUtilities.invokeLater(() -> secondDialogShown.complete(true));
            }
        });
        t2.addActionListener(e -> typedInDialog.complete(true));
        d2.add(t2);
        d2.pack();
        d2.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void pressAndRelease(int keyCode) {
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void clickAt(int x, int y) {
        robot.delay(1000); // needed for GNOME, to give it some time to update internal state after window showing
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static void clickOn(Component component) {
        Point location = component.getLocationOnScreen();
        clickAt(location.x + component.getWidth() / 2, location.y + component.getHeight() / 2);
    }
}
