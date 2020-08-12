// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * @test
 * @summary manual test for JBR-2504
  * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleJListTest
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class AccessibleJListTest extends AccessibleComponentTest {

    private static final String[] NAMES = {"One", "Two", "Three", "Four", "Five"};
    static JWindow window;

    public static void main(String[] args) throws Exception {
        a11yTest = new AccessibleJListTest();
        countDownLatch = a11yTest.createCountDownLatch();

        SwingUtilities.invokeLater(((AccessibleJListTest) a11yTest)::createSimpleList);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }

        countDownLatch = a11yTest.createCountDownLatch();
        SwingUtilities.invokeLater(((AccessibleJListTest) a11yTest)::createCombobox);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }

        countDownLatch = a11yTest.createCountDownLatch();
        SwingUtilities.invokeLater(((AccessibleJListTest) a11yTest)::createPushButton);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }

    @java.lang.Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    public void createSimpleList() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JList in a simple Window.\n\n"
                + "Turn screen reader on, and Tab to the list.\n"
                + "Press the up and down arrow buttons to move through the list.\n\n"
                + "If you can hear menu items tab further and press PASS, otherwise press FAIL.\n";

        JPanel frame = new JPanel();

        JList<String> list = new JList<>(NAMES);

        frame.setLayout(new FlowLayout());
        frame.add(list);
        exceptionString = "Accessible JList simple list test failed!";
        super.createUI(frame, "Accessible JList test");
    }

    public void createCombobox() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JList in a combobox.\n\n"
                + "Turn screen reader on, and Tab to the combobox.\n"
                + "Press the up and down arrow buttons to move through the list.\n\n"
                + "If you can hear combobox items tab further and press PASS, otherwise press FAIL.\n";

        JPanel frame = new JPanel();

        JComboBox<String> combo = new JComboBox<>(NAMES);

        frame.setLayout(new FlowLayout());
        frame.add(combo);
        exceptionString = "Accessible JList combobox test failed!";
        super.createUI(frame, "Accessible JList test");
    }

    public void createPushButton() {
        INSTRUCTIONS = "INSTRUCTIONS:\n"
                + "Check a11y of JList in a popup.\n\n"
                + "Turn screen reader on, and Tab to the show button and press space.\n"
                + "Press the up and down arrow buttons to move through the list.\n\n"
                + "If you can hear popup menu items tab further and press PASS, otherwise press FAIL.\n";

        JPanel frame = new JPanel();

        JButton button = new JButton("show");
        button.setPreferredSize(new Dimension(100, 35));

        button.addActionListener(new ActionListener() {

            final Runnable dispose = () -> {
                window.dispose();
                window = null;
                button.setText("show");
            };

            @Override
            public void actionPerformed(ActionEvent e) {
                if (window == null) {
                    Rectangle bounds = frame.getBounds();
                    window = new JWindow(mainFrame);
                    JList<String> winList = new JList<>(NAMES);
                    winList.addKeyListener(new KeyAdapter() {
                        @Override
                        public void keyPressed(KeyEvent e) {
                            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                                dispose.run();
                            }
                        }
                    });
                    window.add(winList);
                    window.setLocation(bounds.x + bounds.width + 20, bounds.y);
                    window.pack();
                    window.setVisible(true);
                    button.setText("hide (ESC)");
                } else {
                    dispose.run();
                }
            }
        });

        frame.setLayout(new FlowLayout());
        frame.add(button);
        exceptionString = "Accessible JList push button test failed!";
        super.createUI(frame, "Accessible JList test");
    }
}
