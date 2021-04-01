// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-3241
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJComboboxTest
 */

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;

public class AccessibleJComboboxTest extends AccessibleComponentTest {

    @java.lang.Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    void createCombobox() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JCombobox.\n\n"
                + "Turn screen reader on, and Tab to the combobox.\n\n"
                + "If you can hear combobox selected item tab further and press PASS, otherwise press FAIL.";

        JPanel frame = new JPanel();

        String[] NAMES = {"One", "Two", "Three", "Four", "Five"};
        JComboBox<String> combo = new JComboBox<>(NAMES);

        JLabel label = new JLabel("This is combobox:");
        label.setLabelFor(combo);

        frame.setLayout(new FlowLayout());
        frame.add(label);
        frame.add(combo);
        exceptionString = "AccessibleJCombobox test failed!";
        super.createUI(frame, "AccessibleJComboboxTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJComboboxTest test = new AccessibleJComboboxTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createCombobox);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }
}
