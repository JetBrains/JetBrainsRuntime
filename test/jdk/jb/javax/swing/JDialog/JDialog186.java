/*
 * Copyright 2000-2017 JetBrains s.r.o.
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