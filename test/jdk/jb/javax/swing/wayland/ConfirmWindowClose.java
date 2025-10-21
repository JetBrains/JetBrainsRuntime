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

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * @summary Verifies cancelling window close operation does not close the window
 * @requires os.family == "linux"
 * @library /test/lib
 * @key headful
 * @run main/manual ConfirmWindowClose
 */
public class ConfirmWindowClose {
    static final CompletableFuture<RuntimeException> swingError = new CompletableFuture<>();
    static Process testProcess;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            SwingUtilities.invokeLater(ConfirmWindowClose::showTestUI);
        } else {
            try {
                SwingUtilities.invokeAndWait(ConfirmWindowClose::showControlUI);
                swingError.get();
            } finally {
                if (testProcess != null) {
                    testProcess.destroy();
                }
            }
        }
    }

    private static void showTestUI() {
        JFrame frame = new JFrame("ConfirmWindowClose Test");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        JLabel l = new JLabel("<html><h1>INSTRUCTIONS</h1><p>Click on the close button on the window.</p></html>");
        frame.getContentPane().add(l);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int result = JOptionPane.showConfirmDialog(
                        frame,
                        "Do you really want to close it? (click No first)",
                        "Confirm Exit",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (result == JOptionPane.YES_OPTION) {
                    frame.dispose();
                }
            }
        });

        frame.pack();
        frame.setVisible(true);
    }

    private static void showControlUI() {
        JFrame frame = new JFrame("ConfirmWindow Control Frame");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel content = new JPanel();
        var layout = new GridLayout(4, 1, 10, 10);
        content.setLayout(layout);
        JButton runButton = new JButton("Launch Test");
        runButton.addActionListener(e -> {
            ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(ConfirmWindowClose.class.getName(), "runTest");
            try {
                testProcess = pb.start();
            } catch (IOException ex) {
                swingError.complete(new RuntimeException(ex));
                throw new RuntimeException(ex);
            }
        });

        JButton passButton = new JButton("Pass");
        passButton.addActionListener(e -> {swingError.complete(null);});

        JButton failButton = new JButton("Fail");
        failButton.addActionListener(e -> {swingError.completeExceptionally(new RuntimeException("The tester has pressed FAILED"));});

        content.add(runButton);
        content.add(failButton);
        content.add(passButton);
        content.add(new JLabel("<html><center><h1>INSTRUCTIONS</h1></center>" +
                "<p>Press Launch Test</p>" +
                "<p>In the 'ConfirmWindowClose Test' window that appears, click the window close icon on the titlebar.</p>" +
                "<p>When the confirmation dialog appears click No.</p>" +
                "<p>Make sure that the 'ConfirmWindowClose Test' has not disappeared.</p>" +
                "<p>If the window has not disappeared, press Pass here; otherwise, press Fail.</p></html>"));
        frame.setContentPane(content);
        frame.pack();
        frame.setVisible(true);
    }
}
