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

/*
 * @test
 * @requires (os.family == "mac")
 * @run shell build.sh
 * @run main/manual JBRApiTest
 */

import com.jetbrains.JBRFileDialog;

import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.List;

public class FileDialogTest {

    private static final File directory = new File("FileDialogTest").getAbsoluteFile();
    private static void refresh() {
        for (File f : directory.listFiles()) f.delete();
    }

    public static void main(String[] args) throws Exception {
        directory.mkdirs();
        refresh();
        var dialog = new FileDialog((Frame) null);
        dialog.setTitle("Press Save");
        dialog.setDirectory(directory.getAbsolutePath());
        dialog.setFile("PASS");
        dialog.setMode(FileDialog.SAVE);
        dialog.setVisible(true);
        var file = new File(directory, "PASS");
        check(dialog, file);

        refresh();
        file = new File(directory, "OPEN ME");
        file.createNewFile();
        dialog = open(JBRFileDialog.SELECT_FILES_HINT);
        dialog.setVisible(true);
        check(dialog, file);

        refresh();
        file.mkdir();
        dialog = open(JBRFileDialog.SELECT_DIRECTORIES_HINT);
        dialog.setVisible(true);
        check(dialog, file);

        refresh();
        file = new File(directory, "OPEN US");
        file.createNewFile();
        var file2 = new File(directory, "OPEN  US");
        file2.mkdir();
        dialog = open(JBRFileDialog.SELECT_FILES_HINT | JBRFileDialog.SELECT_DIRECTORIES_HINT);
        dialog.setMultipleMode(true);
        dialog.setVisible(true);
        check(dialog, file, file2);

        refresh();
        new File(directory, "CREATE DIRECTORY").createNewFile();
        new File(directory, "AND OPEN IT").createNewFile();
        dialog = open(JBRFileDialog.SELECT_DIRECTORIES_HINT | JBRFileDialog.CREATE_DIRECTORIES_HINT);
        dialog.setVisible(true);
        file = dialog.getFiles()[0];
        dialog.dispose();
        if (!file.isDirectory() || !file.exists()) {
            throw new RuntimeException("Selected file doesn't exist");
        }
    }

    private static FileDialog open(int hints) {
        var dialog = new FileDialog((Frame) null);
        dialog.setMode(FileDialog.LOAD);
        JBRFileDialog.get(dialog).setHints(hints);
        return dialog;
    }

    private static void check(FileDialog dialog, File... expected) {
        List<File> actual = List.of(dialog.getFiles());
        dialog.dispose();
        boolean match = expected.length == actual.size();
        if (match) {
            for (File f : expected) {
                if (!actual.contains(f)) {
                    match = false;
                    break;
                }
            }
        }
        if (!match) {
            throw new RuntimeException("Invalid files, expected: " + Arrays.toString(expected) + ", actual: " + actual);
        }
    }
}
