/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
