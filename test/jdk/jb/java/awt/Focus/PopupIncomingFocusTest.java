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
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-2533 Popup is not focused on click when switching from another application on macOS
 * @key headful
 */

public class PopupIncomingFocusTest {
    private static final CompletableFuture<Boolean> windowOpened = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> popupOpened = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private static Robot robot;
    private static Process otherProcess;
    private static JFrame frame;
    private static JButton button;
    private static JWindow popup;
    private static JTextField field;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoWaitForIdle(true);
        try {
            SwingUtilities.invokeAndWait(PopupIncomingFocusTest::init);
            windowOpened.get(10, TimeUnit.SECONDS);
            launchProcessWithWindow();
            clickAt(button);
            popupOpened.get(10, TimeUnit.SECONDS);
            clickAt(400, 100); // other process' window
            clickAt(field);
            pressEnter();
            result.get(10, TimeUnit.SECONDS);
        }
        finally {
            SwingUtilities.invokeAndWait(PopupIncomingFocusTest::shutdown);
        }
    }

    private static void init() {
        button = new JButton("Open popup");
        button.addActionListener(e -> {
            popup.setVisible(true);
        });

        frame = new JFrame();
        frame.add(button);
        frame.setBounds(50, 50, 200, 100);
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                windowOpened.complete(Boolean.TRUE);
            }
        });

        field = new JTextField(10);
        field.getCaret().setBlinkRate(0); // prevent caret blink timer from keeping event thread running
        field.addActionListener(e -> result.complete(Boolean.TRUE));

        popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        popup.add(field);
        popup.pack();
        popup.setLocation(50, 200);
        popup.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                popupOpened.complete(Boolean.TRUE);
            }
        });

        frame.setVisible(true);
    }

    private static void shutdown() {
        if (frame != null) frame.dispose();
        if (otherProcess != null) otherProcess.destroyForcibly();
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

    private static void pressEnter() {
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
    }

    private static void launchProcessWithWindow() throws Exception {
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        File tmpFile = File.createTempFile("PopupIncomingFocusTest", ".java");
        tmpFile.deleteOnExit();
        Files.writeString(tmpFile.toPath(), "import javax.swing.*;\n" +
                "import java.awt.event.*;\n" +
                "\n" +
                "public class TestWindow {\n" +
                "    public static void main(String[] args) {\n" +
                "        SwingUtilities.invokeLater(() -> {\n" +
                "            JFrame f = new JFrame();\n" +
                "            f.addWindowFocusListener(new WindowAdapter() {\n" +
                "                @Override\n" +
                "                public void windowGainedFocus(WindowEvent e) {\n" +
                "                    System.out.println();\n" +
                "                }\n" +
                "            });\n" +
                "            f.setBounds(300, 50, 200, 100);\n" +
                "            f.setVisible(true);\n" +
                "        });\n" +
                "    }\n" +
                "}\n");
        otherProcess = Runtime.getRuntime().exec(new String[]{javaPath, tmpFile.getAbsolutePath()});
        if (otherProcess.getInputStream().read() == -1) {
            throw new RuntimeException("Error starting process");
        }
    }
}
