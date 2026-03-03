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
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies mouse pointer location is consistent in multi-monitor configurations
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual WLPointerLocation
 */
public class WLPointerLocation {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("WLPointerLocation Test");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            JPanel content = new JPanel();
            var layout = new BorderLayout();
            content.setLayout(layout);
            JLabel l = new JLabel("<html><h1>INSTRUCTIONS</h1>" +
                    "<p>Setup: a multi-monitor configuration where monitors are NOT aligned horizontally, " +
                    "i.e. one is slightly below the other.</p>" +
                    "<p>For each monitor, move the window to that monitor, and press the Test button.</p>" +
                    "<p>When done, press Done.</p>" +
                    "<p>The test passes when every time the Test button was pressed," +
                    " the mouse pointer was within the windows's boundaries.</p></html>");
            content.add(l, BorderLayout.NORTH);

            JButton b = new JButton("Test");
            content.add(b, BorderLayout.CENTER);
            b.addActionListener(e -> {
                Rectangle window = new Rectangle(f.getLocationOnScreen(), f.getSize());
                Point mouse = MouseInfo.getPointerInfo().getLocation();
                boolean windowContainsMouse = window.contains(mouse);
                System.out.println("Window: " + window + "\nMouse: " + mouse + "\nWindow contains mouse: " + windowContainsMouse);
                if (!windowContainsMouse) {
                    swingError.completeExceptionally(new RuntimeException("Window does not contain mouse"));
                }
            });

            JButton done = new JButton("Done");
            content.add(done, BorderLayout.SOUTH);
            done.addActionListener(e -> {
                swingError.complete(null);
            });

            f.setContentPane(content);
            f.pack();
            f.setVisible(true);
        });

        swingError.get();
    }
}