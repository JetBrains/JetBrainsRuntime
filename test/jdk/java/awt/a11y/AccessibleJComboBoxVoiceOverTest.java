/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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
 * @summary Test for VoiceOver-specific issues of JComboBox
 * @author dmitry.drobotov@jetbrains.com
 * @run main/manual AccessibleJComboBoxVoiceOverTest
 * @requires (os.family == "mac")
 */

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.util.concurrent.CountDownLatch;

public class AccessibleJComboBoxVoiceOverTest extends AccessibleComponentTest {

    @java.lang.Override
    public CountDownLatch createCountDownLatch() {
        return new CountDownLatch(1);
    }

    void createCombobox() {
        INSTRUCTIONS = """
                INSTRUCTIONS:
                Check VoiceOver-specific issues of JComboBox.

                Turn VoiceOver on, and Tab to the combo box.

                Use VO+Space shortcut to open the combo box and select a new item.
                Move keyboard focus away from the combo box using Shift+Tab and then back to the combo box by Tab.
                Repeat the same step with VoiceOver cursor navigation using VO+Left and VO+Right.

                If in both cases VoiceOver reads the newly selected value, press PASS, otherwise press FAIL.""";

        JPanel frame = new JPanel();

        String[] NAMES = {"One", "Two", "Three", "Four", "Five"};
        JComboBox<String> combo = new JComboBox<>(NAMES);

        JLabel label = new JLabel("This is combobox:");
        label.setLabelFor(combo);

        frame.setLayout(new FlowLayout());
        frame.add(label);
        frame.add(combo);
        exceptionString = "AccessibleJComboBoxVoiceOver test failed!";
        super.createUI(frame, "AccessibleJComboBoxVoiceOverTest");
    }

    public static void main(String[] args) throws Exception {
        AccessibleJComboBoxVoiceOverTest test = new AccessibleJComboBoxVoiceOverTest();

        countDownLatch = test.createCountDownLatch();
        SwingUtilities.invokeLater(test::createCombobox);
        countDownLatch.await();

        if (!testResult) {
            throw new RuntimeException(a11yTest.exceptionString);
        }
    }
}
