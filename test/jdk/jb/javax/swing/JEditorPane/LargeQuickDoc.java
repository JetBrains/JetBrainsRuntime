/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLEditorKit;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;

/* @test
 * @bug 8151725
 * @summary regression test on JRE-9
 */

public class LargeQuickDoc {

    static private String getText() throws IOException {
        return new String(
                Files.readAllBytes(
                        FileSystems.getDefault().getPath(
                                System.getProperty("test.src", "."), "LargeQuickDoc.txt")));
    }


    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {

            JFrame f = new JFrame();
            JEditorPane editorPane = new javax.swing.JEditorPane("text/html", "");
            editorPane.setEditorKit(new HTMLEditorKit());
            editorPane.setEditable(false);

            JScrollPane scrollPane = new JScrollPane(editorPane);
            scrollPane.setBorder(null);

            try {
                editorPane.setText(getText());
            } catch (IOException | ArrayIndexOutOfBoundsException e) {
                throw new RuntimeException(e);
            }

            f.getContentPane().add(scrollPane);
            f.setSize(500, 500);
            f.setVisible(true);
        });
    }
}