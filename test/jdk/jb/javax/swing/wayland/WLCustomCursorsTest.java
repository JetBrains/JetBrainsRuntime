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

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

/**
 * @test
 * @summary Verifies the ability to use more than one custom cursor
 * @requires os.family == "linux"
 * @key headful
 * @run main/manual WLCustomCursorsTest
 */
public class WLCustomCursorsTest {
    private static JFrame frame;
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(WLCustomCursorsTest::showUI);
            swingError.get();
        } finally {
            if (frame != null) {
                frame.dispose();
            }
        }
    }

    private static void showUI() {
        frame = new JFrame("WLCustomCursorsTest");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel content = new JPanel();
        var layout = new GridLayout(3, 2, 10, 10);
        content.setLayout(layout);
        content.add(new JLabel("<html><h1>INSTRUCTIONS</h1>" +
                "<p>Hover the mouse pointer over both the Pass and Fail button.</p>" +
                "<p>Press Pass iff its shape is different over each button (a circle and a cross).</p>" +
                "<p>Otherwise press Fail.</p></html>"));
        JButton passButton = new JButton("Pass");
        passButton.setCursor(createCustomCursor1());
        passButton.addActionListener(e -> {swingError.complete(null);});
        JButton failButton = new JButton("Fail");
        failButton.setCursor(createCustomCursor2());
        failButton.addActionListener(e -> {swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED"));});
        content.add(failButton);
        content.add(passButton);
        frame.setContentPane(content);
        frame.pack();
        frame.setVisible(true);
    }

    private static Cursor createCustomCursor1() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);

        g.setColor(SystemColor.BLACK);
        g.fillOval(2, 2, size - 4, size - 4);

        g.dispose();

        return Toolkit.getDefaultToolkit().createCustomCursor(image, new Point(8, 8), "circle");
    }

    private static Cursor createCustomCursor2() {
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);

        g.setColor(SystemColor.BLACK);
        g.setStroke(new BasicStroke(3));
        g.drawLine(1, 1, size - 1, size - 1);
        g.drawLine(1, size - 1, size - 1, 1);

        g.dispose();

        return Toolkit.getDefaultToolkit().createCustomCursor(image, new Point(8, 8), "cross");
    }
}
