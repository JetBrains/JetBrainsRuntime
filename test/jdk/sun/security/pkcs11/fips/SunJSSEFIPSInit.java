/*
 * Copyright (c) 2019, Red Hat, Inc.
 *
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
 * @bug 8230923
 * @requires (jdk.version.major == 11) & (os.family == "linux") & (os.arch == "amd64" | os.arch == "x86_64")
 * @modules java.base/com.sun.net.ssl.internal.ssl
 * @library /test/lib
 * @run main/othervm/timeout=30 SunJSSEFIPSInit
 * @author Martin Balao (mbalao@redhat.com)
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class SunJSSEFIPSInit {
    private static String lineSep = System.lineSeparator();
    private static String javaBinPath = System.getProperty("java.home", ".") +
        File.separator + "bin" + File.separator + "java";
    private static String nssConfigFileName = "nss.cfg";
    private static String javaSecConfigFileName = "java.security";
    private static Path tmpDirPath;
    public static void main(String[] args) throws Throwable {
        tmpDirPath = Files.createTempDirectory("tmpdir");
        try {
            deployConfigFiles();
            List<String> cmds = new ArrayList<>();
            cmds.add(javaBinPath);
            cmds.add("-cp");
            cmds.add(System.getProperty("test.classes", "."));
            cmds.add("-Djava.security.properties=" + tmpDirPath +
                    File.separator + javaSecConfigFileName);
            cmds.add(SunJSSEFIPSInitClient.class.getName());
            OutputAnalyzer out = ProcessTools.executeCommand(
                    cmds.toArray(new String[cmds.size()]));
            out.stdoutShouldContain("SunJSSE.isFIPS(): true");
            System.out.println("TEST PASS - OK");
        } finally {
            deleteDir(tmpDirPath);
        }
    }

    private static void deployConfigFiles() throws IOException {
        deployJavaSecurityFile();
        deployNssConfigFile();
    }

    private static void deployJavaSecurityFile() throws IOException {
        int numberOfProviders = Security.getProviders().length;
        StringBuilder sb = new StringBuilder();
        sb.append("security.provider.1=SunPKCS11 " + tmpDirPath +
                File.separator + nssConfigFileName + lineSep);
        sb.append("security.provider.2=com.sun.net.ssl.internal.ssl.Provider" +
                " SunPKCS11-NSS" + lineSep);
        for (int i = 3; i <= numberOfProviders; i++) {
            sb.append("security.provider." + i + "=\"\"" + lineSep);
        }
        writeFile(javaSecConfigFileName, sb.toString());
    }

    private static void deployNssConfigFile() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("name = NSS" + lineSep);
        sb.append("nssDbMode = noDb" + lineSep);
        sb.append("nssModule = crypto" + lineSep);
        writeFile(nssConfigFileName, sb.toString());
    }

    private static void writeFile(String fileName, String fileContent)
            throws IOException {
        try (FileOutputStream fos = new FileOutputStream(new File(tmpDirPath +
                File.separator + fileName))) {
            fos.write(fileContent.getBytes());
        }
    }

    private static void deleteDir(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

