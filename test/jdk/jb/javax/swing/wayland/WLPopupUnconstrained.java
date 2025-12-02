/*
 * Copyright 2025 JetBrains s.r.o.
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

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies popups can be located regardless of the screen edge proximity
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/manual WLPopupUnconstrained
 */
public class WLPopupUnconstrained {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(WLPopupUnconstrained::showUI);
        swingError.get();
    }

    private static void showUI() {
        JFrame frame = new JFrame("Unconstrained popup test");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JLabel label = new JLabel("Right-click here for a popup.");

        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        JWindow popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, label);
        popup.getRootPane().putClientProperty("wlawt.popup_position_unconstrained", Boolean.TRUE);
        popup.setSize(300, 250);
        popup.add(popupContents);
        label.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.setLocation(e.getX(), e.getY());
                    popup.setVisible(true);
                } else {
                    popup.setVisible(false);
                }
            }
        });
        JPanel content = new JPanel();
        var layout = new GridLayout(3, 2, 10, 10);
        content.setLayout(layout);
        content.add(new JLabel("<html><h1>INSTRUCTIONS</h1>" +
                "<p>Locate this window close to the bottom-right edge of the screen.</p>" +
                "<p>Make the popup appear (on the right) such that it is partially outside the screen.</p>" +
                "<p>Press Pass iff the popup indeed crossed the screen edge.</p>" +
                "<p>Otherwise press Fail.</p></html>"));
        content.add(label);
        JButton passButton = new JButton("Pass");
        passButton.addActionListener(e -> {swingError.complete(null);});
        JButton failButton = new JButton("Fail");
        failButton.addActionListener(e -> {swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED"));});
        content.add(failButton);
        content.add(passButton);
        frame.setContentPane(content);
        frame.pack();
        frame.setVisible(true);
    }
}
