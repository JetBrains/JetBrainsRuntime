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


/*
 * @test
 * @summary Verifies that if the machine is equipped with mouse then all the buttons were pressed and released
 * @key headful
 * @run main/othervm/manual MouseBackForwardButtonsTest
 */


import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


public class MouseBackForwardButtonsTest extends JFrame {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();

    public static void main(String[] args) throws InterruptedException, InvocationTargetException, ExecutionException {
        final MouseBackForwardButtonsTest frame;
        {
            final CompletableFuture<MouseBackForwardButtonsTest> frameFuture = new CompletableFuture<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    final var result = new MouseBackForwardButtonsTest();
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


    public MouseBackForwardButtonsTest() {
        super("Test mouse buttons");
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        mouseButtonsTextArea = new JTextArea();
        mouseButtonsTextArea.setLineWrap(true);
        mouseButtonsTextArea.setWrapStyleWord(true);
        mouseButtonsTextArea.setText(
                """
                INSTRUCTION:
                  1. Hover mouse cursor over this text area
                  2. Press each mouse button one by one
                  3. Verify the accuracy of the displayed information regarding the button pressed.
                """
        );
        mouseButtonsTextArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                final int button = e.getButton();

                // On X11, ButtonPress/ButtonRelease native events use the following button indices for different buttons:
                //   1: left mouse button
                //   2: middle mouse button (the primary wheel)
                //   3: right mouse button
                //   4, 5: rotations of the primary mouse wheel (depending on the rotation direction)
                //   6, 7: rotations of the secondary mouse wheel (it's usually used for horizontal scrolling)
                //   >= 8: all extra buttons
                //
                // XToolkit handles them as the following:
                //   1, 2, 3: mapped to MouseEvent as-is
                //   4, 5: mapped to MouseWheelEvent
                //   >= 6: mapped to MouseEvent with the button set to 2 less
                //
                // In other words, MouseEvent.getButton() under XToolkit means the following:
                //   1, 2, 3: left mouse button, middle mouse button, right mouse button respectively
                //   4, 5: rotations of the secondary mouse wheel
                //   >= 6: extra buttons
                if ("sun.awt.X11.XToolkit".equals(Toolkit.getDefaultToolkit().getClass().getName())) {
                    if (button == MouseEvent.BUTTON1) {
                        mouseButtonsTextArea.append("-->Left mouse button pressed.\n");
                    } else if (button == MouseEvent.BUTTON2) {
                        mouseButtonsTextArea.append("-->Middle mouse button pressed.\n");
                    } else if (button == MouseEvent.BUTTON3) {
                        mouseButtonsTextArea.append("-->Right mouse button pressed.\n");
                    } else if (button == 4) {
                        mouseButtonsTextArea.append("-->Horizontal scrolling (direction 1).\n");
                    } else if (button == 5) {
                        mouseButtonsTextArea.append("-->Horizontal scrolling (direction 2).\n");
                    } else if (button == 6) {
                        mouseButtonsTextArea.append("-->Mouse button 6 (Back) pressed.\n");
                    } else if (button == 7) {
                        mouseButtonsTextArea.append("-->Mouse button 7 (Forward) pressed.\n");
                    } else {
                        mouseButtonsTextArea.append("Other mouse button pressed: " + button + "\n");
                    }
                } else {
                    if (button == MouseEvent.BUTTON1) {
                        mouseButtonsTextArea.append("-->Left mouse button pressed.\n");
                    } else if (button == MouseEvent.BUTTON2) {
                        mouseButtonsTextArea.append("-->Middle mouse button pressed.\n");
                    } else if (button == MouseEvent.BUTTON3) {
                        mouseButtonsTextArea.append("-->Right mouse button pressed.\n");
                    } else if (button == 4) { // Check for button 4
                        mouseButtonsTextArea.append("-->Mouse button 4 (Back) pressed.\n");
                    } else if (button == 5) { // Check for button 5
                        mouseButtonsTextArea.append("-->Mouse button 5 (Forward) pressed.\n");
                    } else {
                        mouseButtonsTextArea.append("Other mouse button pressed: " + button + "\n");
                    }
                }
            }
        });
        mouseButtonsTextArea.setEditable(false);

        final JScrollPane mouseButtonTextAreaScrollPane = new JScrollPane(mouseButtonsTextArea, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);

        add(mouseButtonTextAreaScrollPane, BorderLayout.CENTER);

        final JPanel southContainer = new JPanel(new BorderLayout());
        JButton failTestButton = new JButton("FAIL");
        JButton passTestButton = new JButton("PASS");
        southContainer.add(failTestButton, BorderLayout.LINE_START);
        southContainer.add(passTestButton, BorderLayout.LINE_END);
        add(southContainer, BorderLayout.SOUTH);

        pack();
        setSize(400, 250);
        setLocationRelativeTo(null);

        failTestButton.addActionListener(ignored -> swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED")));
        passTestButton.addActionListener(ignored -> swingError.complete(null));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                swingError.completeExceptionally(new RuntimeException("The window was closed not through the \"PASS\"/\"FAIL\" buttons"));
            }
        });
    }


    private final JTextArea mouseButtonsTextArea;
}
