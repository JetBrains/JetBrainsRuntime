/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

/*
 * @test
 * @summary Test implementation of accessibility announcing
 * @author Artem.Semenov@jetbrains.com
 * @run main/manual AccessibleAnnouncerTest
 * @requires (os.family == "windows" | os.family == "mac")
 */

import javax.swing.AccessibleAnnouncer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;

public class AccessibleAnnouncerTest extends AccessibleComponentTest {

    @java.lang.Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

void createTest() {
    INSTRUCTIONS = "";

    JPanel frame = new JPanel();

    JButton button = new JButton("show");
    button.setPreferredSize(new Dimension(100, 35));
JTextField textField = new JTextField("This is text");

    button.addActionListener(new ActionListener() {

        @Override
        public void actionPerformed(ActionEvent e) {
            String str = textField.getText();
            AccessibleAnnouncer.announce(str, AccessibleAnnouncer.ACCESSIBLE_PRIORITY_LOW);
        }
    });

    frame.setLayout(new FlowLayout());
    frame.add(textField);
    frame.add(button);
    exceptionString = "Accessible JList push button with simple window test failed!";
    super.createUI(frame, "Accessible Anouncer test");
}

    public static void main(String[] args)  throws Exception {
        AccessibleAnnouncerTest test = new AccessibleAnnouncerTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createTest);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }
}
