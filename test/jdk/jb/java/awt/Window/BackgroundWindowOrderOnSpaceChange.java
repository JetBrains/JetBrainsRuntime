/*
 * Copyright 2000-2021 JetBrains s.r.o.
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
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;

/**
 * @test
 * @summary Regression test for JBR-3671 Window order changes for a background app on macOS desktop space switch
 * @key headful
 * @requires (os.family == "mac")
 * @compile MacSpacesUtil.java
 * @run main BackgroundWindowOrderOnSpaceChange
 */

public class BackgroundWindowOrderOnSpaceChange {
    private static JFrame frame1;
    private static JFrame frame2;
    private static Process otherProcess;

    public static void main(String[] args) throws Exception {
        MacSpacesUtil.ensureMoreThanOneSpaceExists();
        try {
            SwingUtilities.invokeAndWait(BackgroundWindowOrderOnSpaceChange::initUI);
            launchProcessWithWindow();
            MacSpacesUtil.switchToNextSpace();
            MacSpacesUtil.switchToPreviousSpace();
            Color color = new Robot().getPixelColor(400, 400);
            if (!Color.green.equals(color)) {
                throw new RuntimeException("Frame 1 isn't shown on top. Found color: " + color);
            }
        }
        finally {
            SwingUtilities.invokeAndWait(BackgroundWindowOrderOnSpaceChange::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("BackgroundWindowOrderOnSpaceChange 1");
        frame1.getContentPane().setBackground(Color.green);
        frame1.setBounds(100, 100, 400, 400);
        frame1.setVisible(true);
        frame2 = new JFrame("BackgroundWindowOrderOnSpaceChange 2");
        frame2.getContentPane().setBackground(Color.red);
        frame2.setBounds(300, 300, 400, 400);
        frame2.setVisible(true);
        frame1.toFront();
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
        if (otherProcess != null) otherProcess.destroyForcibly();
    }

    private static void launchProcessWithWindow() throws Exception {
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        File tmpFile = File.createTempFile("BackgroundWindowOrderOnSpaceChange", ".java");
        tmpFile.deleteOnExit();
        Files.writeString(tmpFile.toPath(), "import javax.swing.*;\n" +
                "import java.awt.event.*;\n" +
                "\n" +
                "public class TestWindow {\n" +
                "    public static void main(String[] args) {\n" +
                "        SwingUtilities.invokeLater(() -> {\n" +
                "            JFrame f = new JFrame(\"BackgroundWindowOrderOnSpaceChange 3\");\n" +
                "            f.addWindowFocusListener(new WindowAdapter() {\n" +
                "                @Override\n" +
                "                public void windowGainedFocus(WindowEvent e) {\n" +
                "                    System.out.println();\n" +
                "                }\n" +
                "            });\n" +
                "            f.setBounds(800, 100, 200, 200);\n" +
                "            f.setVisible(true);\n" +
                "        });\n" +
                "    }\n" +
                "}\n");
        otherProcess = Runtime.getRuntime().exec(new String[]{javaPath, tmpFile.getAbsolutePath()});
        if (otherProcess.getInputStream().read() == -1) {
            throw new RuntimeException("Error starting process");
        }
    }
}