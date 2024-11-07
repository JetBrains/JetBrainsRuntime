/*
 * Copyright 2024 JetBrains s.r.o.
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


import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.concurrent.CountDownLatch;

/**
 * @test
 * @summary Verifies that toFront() called immediately following setVisible(false)
 * does not terminate the application
 * @key headful
 * @run main/othervm/manual ActivateAfterHide
 */

public class ActivateAfterHide {
    static JFrame frame1;
    static JFrame frame2;
    static CountDownLatch latch = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(ActivateAfterHide::createAndShowUI);
        latch.await();
    }

    static void createAndShowUI() {
        frame1 = new JFrame("ActivateAfterHide Frame 1");
        frame1.setSize(300, 200);
        frame1.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JButton passButton = new JButton("Pass");
        passButton.addActionListener(e -> {latch.countDown();});
        frame1.add(new JLabel("<html><p>Press the Close button in the second window.</p>" +
                "<p>Test PASSES if this frame is still visible afterwards and FAILS if no UI is visible.</p></html>"), BorderLayout.NORTH);
        frame1.add(passButton, BorderLayout.SOUTH);
        frame1.setVisible(true);

        frame2 = new JFrame("ActivateAfterHide Frame 2");
        frame2.setSize(300, 200);
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            frame2.dispose();
            frame1.toFront();
        });
        frame2.getContentPane().add(closeButton);
        frame2.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame2.setVisible(true);
    }
}
