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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.InputEvent;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies a touchpad pinch gesture is delivered as Ctrl + mouse wheel when enabled via the VM flag
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual/othervm -Dawt.toolkit.name=WLToolkit -Dsun.awt.wl.PinchAsCtrlWheel=true WLPinchZoomTest
 */
public class WLPinchZoomTest {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("WLPinchZoomTest");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            JLabel zoomLabel = new JLabel("Zoom me", SwingConstants.CENTER);
            zoomLabel.setFont(zoomLabel.getFont().deriveFont(24f));
            zoomLabel.addMouseWheelListener(e -> {
                if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                    float size = (float) (zoomLabel.getFont().getSize2D() * Math.pow(1.1, -e.getPreciseWheelRotation()));
                    zoomLabel.setFont(zoomLabel.getFont().deriveFont(Math.max(6f, Math.min(200f, size))));
                }
            });

            JButton pass = new JButton("Pass");
            pass.addActionListener(e -> swingError.complete(null));
            JButton fail = new JButton("Fail");
            fail.addActionListener(e -> swingError.completeExceptionally(new RuntimeException("The tester has pressed Fail")));
            JPanel buttons = new JPanel();
            buttons.add(pass);
            buttons.add(fail);

            f.add(new JLabel("<html><h1>INSTRUCTIONS</h1><ol>" +
                    "<li>Put the mouse pointer over the \"Zoom me\" label.</li>" +
                    "<li>Spread two fingers apart on the touchpad: the label must get bigger.</li>" +
                    "<li>Pinch two fingers together: the label must get smaller.</li>" +
                    "<li>Pinch with three fingers or scroll with two fingers: the label must NOT change its size.</li>" +
                    "</ol></html>"), BorderLayout.NORTH);
            f.add(zoomLabel, BorderLayout.CENTER);
            f.add(buttons, BorderLayout.SOUTH);
            f.setSize(600, 500);
            f.setVisible(true);
        });

        swingError.get();
    }
}
