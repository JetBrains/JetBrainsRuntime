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
import javax.swing.JMenuBar;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies popups and tooltips are disposed of when switching to a different window
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual WLUngrab
 */
public class WLUngrab {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(WLUngrab::showUI);
        swingError.get();
    }

    private static void showUI() {
        JFrame frame = new JFrame("Ungrab test");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JMenuBar menuBar = new JMenuBar();
        JMenu menu1 = new JMenu("Test menu");
        menu1.add(new JMenuItem("item 1"));
        menu1.add(new JMenuItem("item 2"));
        menu1.addSeparator();
        JMenu submenu = new JMenu("submenu...");
        submenu.add(new JMenuItem("subitem1"));
        submenu.add(new JMenuItem("subitem2"));
        submenu.add(new JMenuItem("subitem3"));
        menu1.add(submenu);
        menuBar.add(menu1);

        JMenu menu2 = new JMenu("Another");
        menu2.add(new JMenuItem("test"));
        menuBar.add(menu2);

        frame.setJMenuBar(menuBar);

        JLabel label = new JLabel("Right-click here for a popup-menu.");

        final JPopupMenu popup = new JPopupMenu();
        popup.add(new JMenuItem("popup menu item"));
        popup.add(new JMenuItem("popup menu item 2"));
        popup.add(new JMenuItem("popup menu item 3"));
        label.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup.show(e.getComponent(),
                            e.getX(), e.getY());
                }
            }
        });
        JPanel content = new JPanel();
        var layout = new GridLayout(3, 2, 10, 10);
        content.setLayout(layout);
        content.add(label);
        JButton button = new JButton("Hover here for a tooltip");
        button.setToolTipText("<html><h1>TOOLTIP</h2><p>tooltip text</p></html>");
        content.add(button);
        JButton passButton = new JButton("Pass");
        passButton.addActionListener(e -> {swingError.complete(null);});
        JButton failButton = new JButton("Fail");
        failButton.addActionListener(e -> {swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED"));});
        content.add(failButton);
        content.add(passButton);
        content.add(new JLabel("<html><h1>INSTRUCTIONS</h1>" +
                "<p>Make a tooltip, popup, or pulldown menu appear.</p>" +
                "<p>Switch to a different application window.</p>" +
                "<p>Switch back to this window.</p>" +
                "<p>Press Pass iff the tooltip/popup/menu was closed upon switching back.</p>" +
                "<p>Otherwise press Fail.</p></html>"));
        frame.setContentPane(content);
        frame.pack();
        frame.setVisible(true);
    }
}
