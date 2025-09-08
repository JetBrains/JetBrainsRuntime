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

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import javax.swing.JFrame;
import javax.swing.JLabel;

/**
 * @test
 * @summary Verifies that a small window does not generate GTK warnings in Wayland
 * @requires os.family == "linux"
 * @key headful
 * @library /test/lib
 * @run main WLFrameMinSize
 */
public class WLFrameMinSize {
    private static JFrame frame;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) {
            javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    frame = new JFrame("WLFrameMinSize");
                    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    frame.getContentPane().add(new JLabel("a"));
                    frame.pack();
                    frame.setVisible(true);
                }
            });
            Thread.sleep(2000);
            javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    frame.dispose();
                }
            });
        } else {
            ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                    WLFrameMinSize.class.getName(),
                    "--test");
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.shouldNotContain("Gtk-WARNING");
        }
    }
}
