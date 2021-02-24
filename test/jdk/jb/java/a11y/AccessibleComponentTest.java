// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import java.awt.*;
import java.security.PublicKey;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import javax.swing.JPanel;

public abstract class AccessibleComponentTest {

    protected static volatile boolean testResult = true;
    protected static volatile CountDownLatch countDownLatch;
    protected static String INSTRUCTIONS;
    protected static String exceptionString;
    protected JFrame mainFrame;
    protected static AccessibleComponentTest a11yTest;

    public abstract CountDownLatch createCountDownLatch();

    public void createUI(JPanel component, String testName) {
        mainFrame = new JFrame(testName);
        GridBagLayout layout = new GridBagLayout();
        JPanel mainControlPanel = new JPanel(layout);
        JPanel resultButtonPanel = new JPanel(layout);

        GridBagConstraints gbc = new GridBagConstraints();

        JTextArea instructionTextArea = new JTextArea();
        instructionTextArea.setText(INSTRUCTIONS);
        instructionTextArea.setEditable(false);
        instructionTextArea.setBackground(Color.white);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        mainControlPanel.add(instructionTextArea, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        mainControlPanel.add(component);

        JButton passButton = new JButton("Pass");
        passButton.setActionCommand("Pass");
        passButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mainFrame.dispose();
                countDownLatch.countDown();
            }
        });

        JButton failButton = new JButton("Fail");
        failButton.setActionCommand("Fail");
        failButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testResult = false;
                mainFrame.dispose();
                countDownLatch.countDown();
            }
        });

        gbc.gridx = 0;
        gbc.gridy = 0;
        resultButtonPanel.add(passButton, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        resultButtonPanel.add(failButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        mainControlPanel.add(resultButtonPanel, gbc);

        mainFrame.add(mainControlPanel);
        mainFrame.pack();

        mainFrame.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                mainFrame.dispose();
                countDownLatch.countDown();
            }
        });
        mainFrame.setVisible(true);
    }

    public static class Utils {

        public static JWindow getComponentWindow(Component c) {
            Component parent = c.getParent();
            while ((parent != null) && !(c instanceof JWindow)) {
                c= parent;
                parent = parent.getParent();
            }
            return c instanceof JWindow ? (JWindow)c : null;
        }
    }
}
