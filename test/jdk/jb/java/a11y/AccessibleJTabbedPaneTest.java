// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-3240
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJTabbedPaneTest
 */

import javax.swing.*;
import java.util.concurrent.CountDownLatch;

public class AccessibleJTabbedPaneTest extends AccessibleComponentTest {

    @Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    void createTabPane() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y JTabbedPane of  in a simple Window.\n\n"
                + "Turn screen reader on, and tab to the JTabbedPane.\n"
                + "Use up and down arrow buttons to move through the tabs.\n\n"
                + "If you can hear selected tab names tab further and press PASS, otherwise press FAIL.\n";

        JTabbedPane tabbedPane = new JTabbedPane();

        JPanel panel1 = new JPanel();
        String[] names = {"One", "Two", "Three", "Four", "Five"};
        JList list = new JList(names);
        JLabel fieldName = new JLabel("Text field:");
        JTextField textField = new JTextField();
        fieldName.setLabelFor(textField);
        panel1.add(fieldName);
        panel1.add(textField);
        panel1.add(list);
        tabbedPane.addTab("Tab 1", panel1);
        JPanel panel2 = new JPanel();
        for (int i = 0; i < 5; i++) {
            panel2.add(new JCheckBox("CheckBox " + String.valueOf(i + 1)));
        }
        tabbedPane.addTab("tab 2", panel2);
        JPanel panel = new JPanel();
        panel.add(tabbedPane);

        exceptionString = "Accessible JTabbedPane with renderer simple list test failed!";
        createUI(panel, "AccessibleJTabbedPaneTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJTabbedPaneTest test = new AccessibleJTabbedPaneTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createTabPane);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }
}