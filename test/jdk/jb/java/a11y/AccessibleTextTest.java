// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-3187 and JBR-3188
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleTextTest
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AccessibleTextTest extends AccessibleComponentTest {
    @Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    private void createSimpleLabel() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of simple JLabel in a simple Window.\n\n"
                + "Turn screen reader on.\n"
                + "On MacOS, use the VO navigation keys to read the label text;\n"
                + "ON Windows with JAWS, use window virtualization (insert+alt+w and arrows) to read the label text;\n"
                + "ON Windows with NVDA, use the browse cursor (insert+num4 or insert+num6) to read the label text;\n\n"
                + "If you can hear text from label tab further and press PASS, otherwise press FAIL.";

        JLabel label = new JLabel("this is simpl text label");

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(label);
        exceptionString = "Simpl label test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createOneLineTexField() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of simple JTextField in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text field and type some characters.\n\n"
                + "If you can hear input results according to your screen reader settings, tab further and press PASS, otherwise press FAIL.";

        JTextField textField = new JTextField();

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textField);
        exceptionString = "Simpl text field test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createPassField() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of simple JPasswordField in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the password field and type some characters.\n\n"
                + "If you can hear  sounds specific to your screen reader when interacting with password fields, tab further and press PASS, otherwise press FAIL.";

        JPasswordField passwordField = new JPasswordField();

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(passwordField);
        exceptionString = "Simpl passfield field test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createNamedTextField() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of named simple JTextField in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text field.\n\n"
                + "If you can hear in addition to the fact that this text field is also the names of these fields, tab further and press PASS, otherwise press FAIL.";

        JTextField textField = new JTextField();
        textField.getAccessibleContext().setAccessibleName("This is the first text field:");

        JLabel label = new JLabel("This is the second text field:");
        JTextField secondTextField = new JTextField();
        label.setLabelFor(secondTextField);

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textField);
        panel.add(label);
        panel.add(secondTextField);
        exceptionString = "Named text field test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createTextArea() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of simple JTextArea in a simple Window.\n\n"
                + "Turn screen reader on and press the arrow keys.\n\n"
                + "If you can hear this instructions, tab further and press PASS, otherwise press FAIL.";

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        exceptionString = "Simpl text area test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createEditableTextArea() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of editable JTextArea in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text area and type some characters.\n\n"
                + "If you can hear input results according to your screen reader settings, tab further and press PASS, otherwise press FAIL.";

        JTextArea textArea = new JTextArea();
        JLabel label = new JLabel("0 chars");
        label.setLabelFor(textArea);
        textArea.setEditable(true);
        textArea.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                label.setText(String.valueOf(textArea.getText().length()) + " chars");
            }

            @Override
            public void keyPressed(KeyEvent e) {
                label.setText(String.valueOf(textArea.getText().length()) + " chars");
            }

            @Override
            public void keyReleased(KeyEvent e) {
                label.setText(String.valueOf(textArea.getText().length()) + " chars");
            }
        });

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textArea);
        panel.add(label);
        exceptionString = "Editable text area test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }


    private void createTextPane() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of simple text in JTextPane in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text pane and press the arrow keys.\n\n"
                + "If you can hear text, tab further and press PASS, otherwise press FAIL.";

        String str = "Line 1\nLine 2\nLine 3";
        JTextPane textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setText(str);
        JTextArea textArea = new JTextArea();

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textPane);
        exceptionString = "Simple text pane test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createHTMLTextPane() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of html text in JTextPane in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text pane and press the arrow keys.\n\n"
                + "If you can hear text, tab further and press PASS, otherwise press FAIL.";

        String str = "<html><h1>Header</h1><ul><li>Item 1</li><li>Item 2</li><li>Item 3</li></ul></html>";
        JTextPane textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setContentType("text/html");
        textPane.setText(str);
        JTextArea textArea = new JTextArea();

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textPane);
        exceptionString = "HTML text pane test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }

    private void createEditableTextPane() {
        AccessibleComponentTest.INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of editable JTextPane in a simple Window.\n\n"
                + "Turn screen reader on and press Tab to move to the text pane and type some characters.\n\n"
                + "If you can hear input results according to your screen reader settings, tab further and press PASS, otherwise press FAIL.";

        JTextPane textPane = new JTextPane();
        JLabel label = new JLabel("0 chars");
        label.setLabelFor(textPane);
        textPane.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                label.setText(String.valueOf(textPane.getText().length()) + " chars");
            }

            @Override
            public void keyPressed(KeyEvent e) {
                label.setText(String.valueOf(textPane.getText().length()) + " chars");
            }

            @Override
            public void keyReleased(KeyEvent e) {
                label.setText(String.valueOf(textPane.getText().length()) + " chars");
            }
        });

        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        panel.add(textPane);
        panel.add(label);
        exceptionString = "Editable text pane test failed!";
        super.createUI(panel, "AccessibleTextTest");
    }


    public static void main(String[] args) throws Exception {
        AccessibleTextTest test = new AccessibleTextTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createSimpleLabel);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createOneLineTexField);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createPassField);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createNamedTextField);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createTextArea);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createEditableTextArea);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createTextPane);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createHTMLTextPane);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createEditableTextPane);
        AccessibleComponentTest.countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

    }
}
