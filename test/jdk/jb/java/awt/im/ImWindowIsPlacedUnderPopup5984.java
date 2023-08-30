/*
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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


/*
 * @test
 * @summary A manual test of JBR-5984 bug checks if an IM's window is placed above Swing's/AWT's popups
 * @author Nikita Provotorov
 * @requires (os.family == "mac")
 * @key headful
 * @run main/othervm/manual -Xcheck:jni ImWindowIsPlacedUnderPopup5984
 */


import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


public class ImWindowIsPlacedUnderPopup5984 extends JFrame {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws InterruptedException, InvocationTargetException, ExecutionException {
        final ImWindowIsPlacedUnderPopup5984 frame;
        {
            final CompletableFuture<ImWindowIsPlacedUnderPopup5984> frameFuture = new CompletableFuture<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    final var result = new ImWindowIsPlacedUnderPopup5984();
                    result.setVisible(true);
                    frameFuture.complete(result);
                } catch (Throwable err) {
                    frameFuture.completeExceptionally(err);
                }
            });
            frame = frameFuture.get();
        }

        try {
            final var err = swingError.get();
            if (err != null) {
                throw err;
            }
        } finally {
            if (frame != null) {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }


    public ImWindowIsPlacedUnderPopup5984() {
        super("JBR-5984");
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        popupShowingButton = new JButton("Show a Popup");
        add(popupShowingButton, BorderLayout.NORTH);

        instructionTextArea = new JTextArea();
        instructionTextArea.setLineWrap(true);
        instructionTextArea.setWrapStyleWord(true);
        instructionTextArea.setText(
            """
            INSTRUCTION:
              1. Switch the input source to an input method (e.g. "Pinyin - Simplified");
              2. Press the "Show a Popup" button;
              3. Start typing into the text field on the shown popup;
              4. Check if the input candidates' popup is shown above the java popup:
                4.1. If it's shown above, press the "PASS" button;
                4.2. Otherwise, press the "FAIL" button.
            """
        );
        instructionTextArea.setEditable(false);
        add(instructionTextArea, BorderLayout.CENTER);

        final JPanel southContainer = new JPanel(new BorderLayout());
        failTestButton = new JButton("FAIL");
        passTestButton = new JButton("PASS");
        southContainer.add(failTestButton, BorderLayout.LINE_START);
        southContainer.add(passTestButton, BorderLayout.LINE_END);
        add(southContainer, BorderLayout.SOUTH);

        popupPanel = new JPanel();
        popupTextField = new JTextArea();
        popupTextField.setPreferredSize(new Dimension(200, 125));
        popupPanel.add(popupTextField);

        pack();
        setSize(400, 250);
        setLocationRelativeTo(null);

        popup = new JPopupMenu();
        popup.add(popupPanel);
        popup.pack();

        popupShowingButton.addActionListener(ignored -> popup.show(ImWindowIsPlacedUnderPopup5984.this, 100, 65));

        failTestButton.addActionListener(ignored -> swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED")));
        passTestButton.addActionListener(ignored -> swingError.complete(null));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                swingError.completeExceptionally(new RuntimeException("The window was closed not through the \"PASS\"/\"FAIL\" buttons"));
            }
        });
    }


    private final JButton popupShowingButton;
    private final JTextArea instructionTextArea;
    private final JButton failTestButton;
    private final JButton passTestButton;
    private final JPanel popupPanel;
    private final JTextArea popupTextField;
    private final JPopupMenu popup;
}
