/*
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

/* @test
 * @summary Verifies that a warning is issued when certain bundled libraries
 *          are loaded NOT from 'test.jdk'.
 * @requires os.family == "mac"
 * @library /test/lib
 * @run main LibrariesCheck
 */

import java.awt.Robot;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.Platform;

public class LibrariesCheck {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("runtest")) {
            LibrariesCheck test = new LibrariesCheck();
        } else {
            // Run the test with DYLD_LIBRARY_PATH pointing to a directory
            // with a substitute libfontmanager. JBR is supposed to detect this
            // and complain with a log message.
            String dynamicLibPathEnvVarName = "DYLD_LIBRARY_PATH";
            Path tempDir = Files.createTempDirectory(LibrariesCheck.class.getName());
            String testLibName = System.mapLibraryName("fontmanager");
            Path testLibPath = tempDir.resolve(testLibName);
            try {
                Path jdkLibPath = Platform.libDir();
                Files.copy(jdkLibPath.resolve(testLibName), testLibPath);

                ProcessBuilder pb = ProcessTools.createTestJvm(
                        LibrariesCheck.class.getName(),
                        "runtest");

                Map<String, String> env = pb.environment();
                env.put(dynamicLibPathEnvVarName, tempDir.toString());

                OutputAnalyzer oa = ProcessTools.executeProcess(pb);
                oa.shouldContain("SEVERE").shouldContain(testLibName)
                        .shouldContain(dynamicLibPathEnvVarName);
            } finally {
                Files.delete(testLibPath);
                Files.delete(tempDir);
            }
        }
    }

    JFrame frame;

    LibrariesCheck() throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame = new JFrame("LibrariesCheck");
                JPanel panel = new JPanel();
                panel.add(new JLabel("some text"));
                frame.getContentPane().add(panel);
                frame.pack();
                frame.setVisible(true);
            });

            Robot robot = new Robot();
            robot.waitForIdle();

            SwingUtilities.invokeAndWait(() -> {
                frame.dispose();
            });
        } finally {
            if (frame != null) {
                frame.dispose();
            }
        }
    }
}
