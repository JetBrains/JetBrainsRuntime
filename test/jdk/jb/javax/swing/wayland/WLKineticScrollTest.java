/*
 * Copyright 2026 JetBrains s.r.o.
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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies kinetic scrolling with a touchpad when enabled via the VM flag
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual/othervm -Dawt.toolkit.name=WLToolkit -Dsun.awt.wl.KineticScrolling=true WLKineticScrollTest
 */
public class WLKineticScrollTest {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("WLKineticScrollTest");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= 5000; i++) {
                text.append("Line ").append(i).append(": lorem ipsum dolor sit amet ".repeat(10)).append('\n');
            }

            JButton pass = new JButton("Pass");
            pass.addActionListener(e -> swingError.complete(null));
            JButton fail = new JButton("Fail");
            fail.addActionListener(e -> swingError.completeExceptionally(new RuntimeException("The tester has pressed Fail")));
            JPanel buttons = new JPanel();
            buttons.add(pass);
            buttons.add(fail);

            f.add(new JLabel("<html><h1>INSTRUCTIONS</h1>Put the mouse pointer over the text and check that:<ol>" +
                    "<li>After a quick two-finger swipe the text keeps scrolling and smoothly slows down.</li>" +
                    "<li>Touching the touchpad, a mouse click, or a key press stops the inertia immediately.</li>" +
                    "<li>There's no inertia after a pause before lifting the fingers, for a mouse wheel, " +
                    "and with Ctrl pressed.</li>" +
                    "</ol></html>"), BorderLayout.NORTH);
            f.add(new JScrollPane(new JTextArea(text.toString())), BorderLayout.CENTER);
            f.add(buttons, BorderLayout.SOUTH);
            f.setSize(700, 600);
            f.setVisible(true);
        });

        swingError.get();
    }
}
