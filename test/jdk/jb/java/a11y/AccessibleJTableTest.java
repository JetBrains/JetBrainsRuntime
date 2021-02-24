// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-2649
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJTableTest
 */

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AccessibleJTableTest extends AccessibleComponentTest {
    private static final String[] columnNames = {"One", "Two", "Three"};
    private static final String[][] data = {
            {"One1", "Two1", "Three1"},
            {"One2", "Two2", "Three2"},
            {"One3", "Two3", "Three3"},
            {"One4", "Two4", "Three4"},
            {"One5", "Two5", "Three5"}
    };

    @Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    public void  createUI() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JTable in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the table.\n"
                + "On Windows press the arrow buttons to move through the table.\n\n"
                + "On MacOS, use the up and down arrow buttons to move through rows, and VoiceOver fast navigation to move through columns.\n\n"
                + "If you can hear table cells tab further and press PASS, otherwise press FAIL.\n";

        JTable table = new JTable(data, columnNames);
        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(table);
        panel.add(scrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTable test failed!";
        super.createUI(panel, "AccessibleJTableTest");
    }

    public void  createUINamed() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of named JTable in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the table.\n"
                + "Press the tab button to move to second table.\n\n"
                + "If you can hear second table name the \"Second table\" tab further and press PASS, otherwise press FAIL.\n";

        JTable table = new JTable(data, columnNames);
        JTable secondTable = new JTable(data, columnNames);
        secondTable.getAccessibleContext().setAccessibleName("Second table");
        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(table);
        JScrollPane secondScrollPane = new JScrollPane(secondTable);
        panel.add(scrollPane);
        panel.add(secondScrollPane);
        panel.setFocusable(false);
        exceptionString = "AccessibleJTable test failed!";
        super.createUI(panel, "AccessibleJTableTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJTableTest test = new AccessibleJTableTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createUI);
        countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeAndWait(test::createUINamed);
        countDownLatch.await(15, TimeUnit.MINUTES);
        if (!testResult) {
            throw new RuntimeException(exceptionString);
        }
    }
}
