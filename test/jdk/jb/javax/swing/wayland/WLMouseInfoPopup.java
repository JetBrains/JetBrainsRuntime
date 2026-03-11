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
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies mouse pointer location is consistent over popups in multi-monitor configurations.
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual WLMouseInfoPopup
 */
public class WLMouseInfoPopup {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(WLMouseInfoPopup::showUI);
        swingError.get();
    }

    private static void showUI() {
        JFrame f = new JFrame("WLMouseInfoPopup Test");
        JButton btn = new JButton("Open popup");
        f.setLayout(new BorderLayout());
        f.add(btn, BorderLayout.NORTH);

        String instructions = "<html><h1>INSTRUCTIONS</h1>" +
                "<p>In a multi-monitor configuration, perform the following steps placing this window" +
                "on each of the monitors.</p>" +
                "<p>1. Click 'Open popup' button.</p>" +
                "<p>2. A popup with 'Mouse info' button will appear.</p>" +
                "<p>3. Click the 'Mouse info' button in the popup.</p>" +
                "<p>The test automatically verifies the mouse location and fails if the conditions are not met.</p>" +
                "<p>If the window stays open at the end, press Pass.</p></html>";
        JLabel instructionLabel = new JLabel(instructions);
        f.add(instructionLabel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel();
        JButton passButton = new JButton("Pass");
        passButton.addActionListener(e -> swingError.complete(null));
        controlPanel.add(passButton);
        f.add(controlPanel, BorderLayout.SOUTH);

        btn.addActionListener(ee -> {
            JPanel p = new JPanel(new BorderLayout());
            JButton b = new JButton("Mouse info");
            p.add(b, BorderLayout.CENTER);
            b.addActionListener(e -> {
                try {
                    Rectangle window = new Rectangle(p.getLocationOnScreen(), p.getSize());
                    Point mouse = MouseInfo.getPointerInfo().getLocation();
                    boolean windowContainsMouse = window.contains(mouse);
                    System.out.printf("Window: %s%nMouse: %s%nWindow contains mouse?: %s%n", window, mouse, windowContainsMouse);

                    var mouseDevice = MouseInfo.getPointerInfo().getDevice();
                    var frameDevice = b.getGraphicsConfiguration().getDevice();
                    System.out.printf("Mouse device: %s%nFrame device: %s%n", mouseDevice, frameDevice);

                    if (!windowContainsMouse) {
                        swingError.completeExceptionally(new RuntimeException("Window does not contain mouse"));
                    }
                    if (mouseDevice != frameDevice) {
                        swingError.completeExceptionally(new RuntimeException("Mouse device is not the same as popup device"));
                    }
                } catch (RuntimeException ex) {
                    swingError.completeExceptionally(ex);
                }
            });
            Point point = f.getLocationOnScreen();
            PopupFactory.getSharedInstance().getPopup(btn, p, point.x + f.getWidth() - 15, point.y + 25).show();
        });

        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.pack();
        f.setVisible(true);
    }
}
