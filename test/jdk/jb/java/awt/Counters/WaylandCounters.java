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
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @test
 * @summary Verifies that java2d.native.frames performance counter gets
 *          updated for a Swing application running on Wayland
 * @key headful
 * @requires (os.family == "linux")
 * @library /test/lib
 * @run main WaylandCounters
 */

public class WaylandCounters {
    private static int counter = 25;

    public static void main(String[] args) throws Exception {
        Toolkit tk = Toolkit.getDefaultToolkit();
        if (!tk.getClass().getName().equals("sun.awt.wl.WLToolkit")) return;

        if (args.length > 0) {
            runSwingApp();
        } else {
            {
                ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-Dawt.window.counters=stderr",
                        UpdateWindowsCounter.class.getName(),
                        "test");

                OutputAnalyzer output = new OutputAnalyzer(pb.start());
                output.stderrShouldContain("java2d.native.frames per second");
                output.stdoutShouldBeEmpty();
            }

            {
                ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-Dawt.window.counters=java2d.native.frames,stdout",
                        UpdateWindowsCounter.class.getName(),
                        "test");

                OutputAnalyzer output = new OutputAnalyzer(pb.start());
                output.stdoutShouldContain("java2d.native.frames per second");
                output.stdoutShouldNotContain("swing.RepaintManager.updateWindows");
                output.stderrShouldBeEmpty();
            }

            {
                ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-Dawt.window.counters=stderr,java2d.native.frames",
                        UpdateWindowsCounter.class.getName(),
                        "test");

                OutputAnalyzer output = new OutputAnalyzer(pb.start());
                output.stderrShouldContain("java2d.native.frames per second");
                output.stderrShouldNotContain("swing.RepaintManager.updateWindows");
                output.stdoutShouldBeEmpty();
            }

            {
                ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-Dawt.window.counters=stdout",
                        UpdateWindowsCounter.class.getName(),
                        "test");

                OutputAnalyzer output = new OutputAnalyzer(pb.start());
                output.stderrShouldBeEmpty();
                output.shouldContain("java2d.native.frames per second");
            }

            {
                ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder("-Dawt.window.counters=java2d.native.frames",
                        UpdateWindowsCounter.class.getName(),
                        "test");

                OutputAnalyzer output = new OutputAnalyzer(pb.start());
                output.stdoutShouldContain("java2d.native.frames per second");
                output.stdoutShouldNotContain("swing.RepaintManager.updateWindows");
                output.stderrShouldBeEmpty();
            }

        }
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