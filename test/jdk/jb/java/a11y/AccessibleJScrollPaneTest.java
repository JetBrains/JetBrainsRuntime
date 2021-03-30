// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-3239
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJScrollPaneTest
 */

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;

public class AccessibleJScrollPaneTest extends AccessibleComponentTest {

    @java.lang.Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    void createScrollPane() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JScrollPane in a simple Window.\n\n"
                + "Turn screen reader on, and tab to the table.\n"
                + "this table has 10 rows and 10 columns, few cells are invisible.\n\n"
                + "On Windows press arrow buttons to move through the table.\n\n"
                + "On MacOS, use up and down arrow buttons to move through rows, and VoiceOver fast navigation to move through columns.\n\n"
                + "If you can hear table cells tab further and press PASS, otherwise press FAIL.\n";

        final  int n = 10;

        String[][] data = new String[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                data[i][j] = "Cell " + String.valueOf(i) + ":" + String.valueOf(j);
            }
        }
        String[] columnNames = new String[n];
        for (int i = 0; i < n; i++) {
            columnNames[i] = "Header " + String.valueOf(i);
        }

        JTable table = new JTable(data, columnNames);
        table.setPreferredScrollableViewportSize(new Dimension(table.getPreferredScrollableViewportSize().width / 2, table.getRowHeight() * 5));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane);

        exceptionString = "AccessibleJScrollPane test failed!";
        super.createUI(panel, "AccessibleJScrollTest");
    }

    public static void main(String[] args) throws  Exception {
        AccessibleJScrollPaneTest test = new AccessibleJScrollPaneTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createScrollPane);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }
}
