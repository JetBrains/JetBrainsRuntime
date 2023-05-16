/*
 * Copyright 2022-2023 JetBrains s.r.o.
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
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
/*
 * @test
 * @requires os.family == "mac"
 * @summary Regression test for JBR-5627.
 *          The test downloads async-profiler-2.9 from github and runs the simple UI application AsyncProfilerHelper
 *          with profiling. It checks that profiling completes successfully.
 * @modules java.base/jdk.internal.misc
 * @run main AsyncProfilerRunnerTest
 */

public class AsyncProfilerRunnerTest {
    static final String ASYNC_PROFILER_2_9_MACOS = "async-profiler-2.9-macos";
    static final String ASYNC_PROFILER_2_9_MACOS_ZIP = ASYNC_PROFILER_2_9_MACOS + ".zip";
    static final String ASYNC_PROFILER_2_9_MACOS_URL = "https://github.com/jvm-profiling-tools/async-profiler/releases/download/v2.9/" + ASYNC_PROFILER_2_9_MACOS_ZIP;

    static final String CURRENT_DIR=System.getProperty("user.dir");
    static final String TEST_JDK = System.getProperty("test.jdk");

    public static void main(String[] args) {

        try {
            downloadFile(ASYNC_PROFILER_2_9_MACOS_URL, ASYNC_PROFILER_2_9_MACOS_ZIP);
            System.out.println("File downloaded successfully!");

            extractZipFile(ASYNC_PROFILER_2_9_MACOS_ZIP, CURRENT_DIR);
            System.out.println("File extracted successfully!");

            String[] profilerCommand =  new String[] {
                    TEST_JDK + "/bin/java",
                    "-agentpath:"
                            + CURRENT_DIR + "/" + ASYNC_PROFILER_2_9_MACOS
                            + "/build/libasyncProfiler.so=version,start,event=cpu,jfr,threads,jfrsync=profile,file=profile.html,title=test",
                    "AsyncProfilerHelper"
            };

            System.out.println("running the helper against: " + profilerCommand[0]);
            System.out.println("          agent parameters: "  + profilerCommand[1]);
            ProcessResult result = runProfilerCommand(profilerCommand);
            System.out.println("Process output:\n" + result.output());
            System.out.println("Process error:\n" + result.error());

            int exitCode = result.exitCode();
            if (exitCode != 0)
                throw new RuntimeException("Process exit code: " + exitCode);

        } catch (IOException | InterruptedException e) {
            System.out.println("An error occurred: " + e.getMessage());
        }
    }

    public static void downloadFile(String fileUrl, String destinationPath) throws IOException {
        System.out.println("Downloading: " + fileUrl);

        HttpURLConnection connection = (HttpURLConnection) new URL(fileUrl).openConnection();

        if (connection.getResponseCode() != 200)
            throw new RuntimeException(connection.getResponseMessage());

        Files.copy(connection.getInputStream(), Paths.get(destinationPath), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void extractZipFile(String zipFilePath, String extractPath) throws IOException {
        File destDir = new File(extractPath);
        if (!destDir.exists()) {
            destDir.mkdir();
        }
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry = zipIn.getNextEntry();
            while (entry != null) {
                String filePath = extractPath + File.separator + entry.getName();
                if (!entry.isDirectory()) {
                    extractFile(zipIn, filePath);
                } else {
                    File dir = new File(filePath);
                    dir.mkdir();
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
        }
    }

    public static void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[4096];
        int read;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }

    public static ProcessResult runProfilerCommand(String[] profilerCommand) throws IOException, InterruptedException {

        Process process = Runtime.getRuntime().exec(profilerCommand);
        process.waitFor();

        String output = readStream(process.getInputStream());
        String error = readStream(process.getErrorStream());
        int exitCode = process.exitValue();

        return new ProcessResult(output, error, exitCode);
    }

    public static String readStream(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        return output.toString();
    }

    record ProcessResult(String output, String error, int exitCode) {
        public ProcessResult(String output, String error, int exitCode) {
            this.output = output;
            this.error = error;
            this.exitCode = exitCode;
        }
    }
}

class AsyncProfilerHelper {
    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeLater(AsyncProfilerHelper::initUI);
        } finally {
            SwingUtilities.invokeAndWait(AsyncProfilerHelper::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("AsyncProfilerHelper");
        frame.setBounds(200, 200, 500, 300);
        frame.setVisible(true);
        JWindow w = new JWindow(frame);
        w.setBounds(300, 250, 200, 200);
        w.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }
}