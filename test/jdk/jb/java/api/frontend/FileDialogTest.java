/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
