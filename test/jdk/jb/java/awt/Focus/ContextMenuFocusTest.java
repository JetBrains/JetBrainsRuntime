/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.CountDownLatch;

/**
 * @test
 * @summary Regression test for JBR-7072 Wayland: clicks on items of floating context menus are ignored
 * @key headful
 *
 * @run main/manual ContextMenuFocusTest
 */

public class ContextMenuFocusTest {
    static volatile boolean passed = false;
    static CountDownLatch latch = new CountDownLatch(1);
    static JFrame frame;
    public static void main(String[] args) throws InterruptedException {
        runTest();
        latch.await();
        frame.dispose();
        if (!passed) throw new RuntimeException("Test failed");
    }

    private static void runTest() {
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("Focus Events Test");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            try {
                JButton button = new JButton("Button");
                button.addFocusListener(new FocusListener() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        button.setBackground(Color.GREEN);
                    }

                    @Override
                    public void focusLost(FocusEvent e) {
                        if (!e.isTemporary()) {
                            button.setBackground(Color.RED);
                        }
                    }
                });

                JPopupMenu popup = new JPopupMenu("Edit");
                popup.add(new JMenuItem("Cut"));
                popup.add(new JMenuItem("Copy"));
                popup.add(new JMenuItem("Paste"));

                button.addMouseListener(new MouseAdapter() {
                    public void mousePressed(MouseEvent me) {
                        if (SwingUtilities.isRightMouseButton(me)) {
                            popup.show(me.getComponent(), me.getX(), me.getY());
                        }
                    }
                });

                JButton passButton = new JButton("PASS");
                passButton.addActionListener((ActionEvent e) -> {
                    passed = true;
                    latch.countDown();
                });

                JButton failButton = new JButton("FAIL");
                failButton.addActionListener((ActionEvent e) -> {
                    passed = false;
                    latch.countDown();
                });


                frame.add(new JLabel("<html><h1>Instructions</h1>" +
                                "<p>The button below stays green as long as it is in focus or " +
                                "has <em>temporarily</em> lost focus.</p>" +
                                "<ul><li>Make sure the button below is in focus (green);<br>left-click it if it is not in focus.</li>" +
                                "<li>Right-click the button below so that the context menu appears.</li>" +
                                "<li>If the button turned red, the test has failed. Press the FAIL button.</li>" +
                                "<li>If the button remained green, the test has passed. Press the PASS button</li></ul></html>"),
                        BorderLayout.CENTER);
                frame.add(button, BorderLayout.SOUTH);
                frame.add(passButton, BorderLayout.EAST);
                frame.add(failButton, BorderLayout.WEST);
                frame.setSize(500, 800);
                frame.setVisible(true);

                button.requestFocusInWindow();
            } catch (Exception e) {
                frame.dispose();
            }
        });
    }
}