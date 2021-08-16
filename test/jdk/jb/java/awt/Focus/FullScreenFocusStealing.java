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

import com.apple.eawt.Application;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;

/**
 * @test
 * @summary Regression test for JBR-3686 Background window steals focus when converted to full screen on macOS
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 */

public class FullScreenFocusStealing {
    private static JFrame frame;
    private static Process otherProcess;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(FullScreenFocusStealing::initUI);
            launchProcessWithWindow();
            Application.getApplication().requestToggleFullScreen(frame);
            Thread.sleep(1000);
            assertProcessWindowIsStillFocused();
        }
        finally {
            SwingUtilities.invokeAndWait(FullScreenFocusStealing::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("FullScreenFocusStealing");
        frame.setBounds(100, 100, 200, 200);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
        if (otherProcess != null) otherProcess.destroyForcibly();
    }

    private static void assertProcessWindowIsStillFocused() throws Exception {
        otherProcess.getOutputStream().write('\n');
        otherProcess.getOutputStream().flush();
        if (otherProcess.getInputStream().read() != '1') {
            throw new RuntimeException("Process window lost focus");
        }
    }

    private static void launchProcessWithWindow() throws Exception {
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        File tmpFile = File.createTempFile("FullScreenFocusStealing", ".java");
        tmpFile.deleteOnExit();
        Files.writeString(tmpFile.toPath(), "import javax.swing.*;\n" +
                "import java.awt.event.*;\n" +
                "\n" +
                "public class TestWindow {\n" +
                "    private static JFrame frame;\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        SwingUtilities.invokeLater(() -> {\n" +
                "            frame = new JFrame(\"FullScreenFocusStealing 2\");\n" +
                "            frame.addWindowFocusListener(new WindowAdapter() {\n" +
                "                @Override\n" +
                "                public void windowGainedFocus(WindowEvent e) {\n" +
                "                    System.out.println();\n" +
                "                }\n" +
                "            });\n" +
                "            frame.setBounds(100, 400, 200, 200);\n" +
                "            frame.setVisible(true);\n" +
                "        });\n" +
                "        System.in.read();\n" +
                "        System.out.println(frame.isFocused() ? '1' : '0');\n" +
                "    }\n" +
                "}\n");
        otherProcess = Runtime.getRuntime().exec(new String[]{javaPath, tmpFile.getAbsolutePath()});
        if (otherProcess.getInputStream().read() == -1) {
            throw new RuntimeException("Error starting process");
        }
    }
}