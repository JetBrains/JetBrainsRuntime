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

import java.awt.Point;
import java.awt.Robot;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/* @test
 * @summary regression test on JRE-186 Modal dialogs (Messages) shouldn't popup IDEA when another application is active
 * @run main/othervm/timeout=360 JDialog186
 */

public class JDialog186 {

    private static JFrame frame = new JFrame("JDialog186");
    private static JDialog dialog;
    private static Process process;
    static final Object lock = new Object();

    private static Runnable modalDialogThread = new Runnable() {
        @Override
        public void run() {
            dialog = new JDialog(frame, true);
            dialog.setTitle("Modal input");
            dialog.getContentPane().add(new JTextArea());
            dialog.setLocation(new Point(20, 100));
            dialog.setSize(350, 100);
            dialog.setVisible(true);
        }
    };

    private static MainWindowFocusListener focusListener = new MainWindowFocusListener();
    private static String javaExecutable = System.getProperty("java.home") + File.separatorChar + "bin" + File.separatorChar + "java";


    static class MainWindowFocusListener implements WindowFocusListener {
        @Override
        public void windowGainedFocus(WindowEvent windowEvent) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void windowLostFocus(WindowEvent windowEvent) {
            frame.removeWindowFocusListener(focusListener);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {

            }
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private static void checkAuxProcess() {
        new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            process.getErrorStream();
            String line;
            BufferedReader input = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            try {
                while ((line = input.readLine()) != null) {
                    System.out.println(line);
                }
                input.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (process.exitValue() == 1) {
                System.err.println("Error: Could not launch auxiliary application");
                System.exit(1);
            }
        }).start();
    }

    public static void main(String[] args) throws Exception {
        frame.setSize(400, 400);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        try {
            frame.addWindowFocusListener(focusListener);
            frame.setVisible(true);
            synchronized (lock) {
                while (!frame.isFocused()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            System.out.println("launching helper");
            process = Runtime.getRuntime().exec(new String[]{javaExecutable, "-classpath",
                    System.getProperty("java.class.path"), "JDialog186Aux"});
            checkAuxProcess();

            synchronized (lock) {
                while (frame.isFocused()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException();
        }

        SwingUtilities.invokeLater(modalDialogThread);
        process.waitFor();
        dialog.dispose();
        frame.dispose();

        if (process.exitValue() == 19) throw new RuntimeException("Modal Dialog expects TextArea to be empty");
    }
}

class JDialog186Aux {

    private static final char[] expectedText = ",-./0123456789;=ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static JDialog186.MainWindowFocusListener focusListener = new JDialog186.MainWindowFocusListener();

    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(100);

        JFrame frame = new JFrame("JDialog186Aux");
        frame.addWindowFocusListener(focusListener);
        JEditorPane editorPane = new JEditorPane();

        frame.getContentPane().add(editorPane);
        frame.setLocation(new Point(400, 0));
        frame.setSize(350, 100);
        frame.addWindowFocusListener(focusListener);
        frame.setVisible(true);

        synchronized (JDialog186.lock) {
            while (!frame.isFocused()) {
                try {
                    JDialog186.lock.wait();
                } catch (InterruptedException ignored) {

                }
            }
        }
        int keyCode;
        for (char c : expectedText) {
            keyCode = KeyStroke.getKeyStroke((c), 0).getKeyCode();
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        }

        frame.dispose();

        if (!editorPane.getText().equals(new String(expectedText).toLowerCase())) System.exit(19);
    }
}