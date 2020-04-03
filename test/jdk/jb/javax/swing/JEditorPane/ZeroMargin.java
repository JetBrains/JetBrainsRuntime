/*
 * Copyright 2000-2023 JetBrains s.r.o.
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


import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import static java.awt.event.KeyEvent.VK_A;

/**
 * @test
 * @summary The test checks that height of JEditorPane with zero top and bottom insets is updated after changing text
 * @run main/othervm ZeroMargin
 */

public class ZeroMargin implements Runnable {

    private JFrame f;
    private JEditorPane htmlPane;
    private JEditorPane rtfPane;
    private JEditorPane plainPane;

    public static void main(String[] args) throws Exception {

        ZeroMargin test = new ZeroMargin();
        try {
            Robot r = new Robot();
            SwingUtilities.invokeAndWait(test);
            r.waitForIdle();
            r.keyPress(VK_A);
            r.keyRelease(VK_A);
            r.delay(500);
            if (test.htmlPane.getHeight() == 0) {
                throw new RuntimeException("Height of JEditorPane with text/html type was not updated");
            }
            if (test.rtfPane.getHeight() == 0) {
                throw new RuntimeException("Height of JEditorPane with text/rtf type was not updated");
            }
            if (test.plainPane.getHeight() == 0) {
                throw new RuntimeException("Height of JEditorPane with text/plain type was not updated");
            }
        } finally {
            SwingUtilities.invokeAndWait(() ->test.f.dispose());
        }
    }

    private void initEditorPane(JEditorPane pane, KeyAdapter adapter) {
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setMargin(new Insets(0, 0, 0, 0));
        pane.addKeyListener(adapter);
    }

    @Override
    public void run() {
        f = new JFrame();
        htmlPane = new JEditorPane("text/html", "");
        rtfPane = new JEditorPane("text/rtf", "");
        plainPane = new JEditorPane("text/plain", "");

        KeyAdapter adapter = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                super.keyPressed(e);
                if (e.getKeyCode() == VK_A) {
                    htmlPane.setText("<html><body>html string</body></html>");
                    rtfPane.setText("{\\rtf1 rtf string}");
                    plainPane.setText("plain string");
                }
            }
        };
        initEditorPane(htmlPane, adapter);
        initEditorPane(rtfPane, adapter);
        initEditorPane(plainPane, adapter);

        f.add(htmlPane, BorderLayout.NORTH);
        f.add(rtfPane, BorderLayout.CENTER);
        f.add(plainPane, BorderLayout.SOUTH);
        f.setSize(300, 200);
        f.setVisible(true);
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    }
}
