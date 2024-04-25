/*
 * Copyright 2024 JetBrains s.r.o.
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


import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @test
 * @summary Verifies that swing.RepaintManager.updateWindows performance counter gets
 *          updated for a Swing application
 * @key headful
 * @library /test/lib
 * @run main UpdateWindowsCounter
 */

public class UpdateWindowsCounter {
    private static int counter = 25;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runSwingApp();
        } else {
            runOneTest("-Dawt.window.counters=stdout", "swing.RepaintManager.updateWindows per second");
            runOneTest("-Dawt.window.counters=swing.RepaintManager.updateWindows,stdout", "swing.RepaintManager.updateWindows per second");
            runOneTest("-Dawt.window.counters=stderr,swing.RepaintManager.updateWindows", "swing.RepaintManager.updateWindows per second");
            runOneTest("-Dawt.window.counters", "swing.RepaintManager.updateWindows per second");
            runOneTest("-Dawt.window.counters=", "swing.RepaintManager.updateWindows per second");
            runOneTest("-Dawt.window.counters=swing.RepaintManager.updateWindows", "swing.RepaintManager.updateWindows per second");

            runOneNegativeTest("", "swing.RepaintManager.updateWindows");
            runOneNegativeTest("-Dawt.window.counters=UNCOUNTABLE", "swing.RepaintManager.updateWindows");
        }
    }

    private static void runOneTest(String vmArg, String expectedOutput) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(vmArg,
                UpdateWindowsCounter.class.getName(),
                "test");

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldContain(expectedOutput);
    }

    private static void runOneNegativeTest(String vmArg, String unexpectedOutput) throws Exception {
        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(vmArg,
                UpdateWindowsCounter.class.getName(),
                "test");

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldNotContain(unexpectedOutput);
    }

    private static void runSwingApp() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Timer App");
            frame.setSize(300, 200);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            JLabel label = new JLabel("---", SwingConstants.CENTER);
            frame.getContentPane().add(label);

            Timer timer = new Timer(100, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    counter--;
                    label.setText(String.valueOf(counter));

                    if (counter == 0) {
                        ((Timer) e.getSource()).stop();
                        frame.dispose();
                    }
                }
            });

            frame.setVisible(true);
            timer.start();
        });
    }
}