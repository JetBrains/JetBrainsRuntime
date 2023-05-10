/*
 * Copyright 2000-2022 JetBrains s.r.o.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @test
 * @summary VerifyDependencies checks readability verifies that a Linux shared
 *          library has no dependency on symbols from glibc version higher than 2.17
 * @run main VerifyDependencies
 * @requires (os.family == "linux")
 */

public class VerifyDependencies {

    public static void verifyLibrary(String libraryPath) throws IOException {
        Process process;
        BufferedReader reader;
        String line;

        System.out.println("checking " + libraryPath);
        System.out.println("=========================");

        process = Runtime.getRuntime().exec("readelf -Ws " + libraryPath);
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
            if (line.contains("GLIBC_")) {
                String version = extractVersion(line);
                if (compareVersions(version, "2.17") > 0) {
                    throw new RuntimeException(libraryPath + " has a dependency on glibc version " + version);
                }
            }
        }
        System.out.println(libraryPath + " has no dependency on glibc version higher than 2.17");
    }

    private static String extractVersion(String line) {
        int glibcIndex = line.indexOf("GLIBC_");
        int versionDelimiter = line.indexOf(" ", glibcIndex);
        if ( versionDelimiter >= 0 && versionDelimiter < line.length())
            return line.substring(glibcIndex + 6, versionDelimiter);

        return line.substring(line.indexOf("GLIBC_") + 6);
    }

    private static int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int major1 = Integer.parseInt(parts1[0]);
        int minor1 = parts1.length > 1 ? Integer.parseInt(parts1[1]) : 0;

        int major2 = Integer.parseInt(parts2[0]);
        int minor2 = parts2.length > 1 ? Integer.parseInt(parts2[1]) : 0;

        if (major1 == major2) {
            System.out.printf("\tcomparing %s with %s\n", minor1, minor2);
            return Integer.compare(minor1, minor2);
        }

        return Integer.compare(major1, major2);
    }

    private static void findFiles(File directory, FilenameFilter filter) throws IOException {
        File[] files = directory.listFiles(filter);

        if (files.length == 0) {
            return;
        } else {
            for (File file : files) {
                System.out.println(file.getAbsolutePath());
                verifyLibrary(file.getAbsolutePath());
            }
        }

        for (File subDirectory : directory.listFiles(File::isDirectory)) {
            findFiles(subDirectory, filter);
        }
    }

    private static void findInDirectory(String directoryPath, boolean findLibs) throws IOException {
        String libPattern = ".so";

        File directory = new File(directoryPath);

        if (!directory.isDirectory()) {
            System.out.println(directoryPath + " is not a directory.");
            System.exit(1);
        }

        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return findLibs ? name.endsWith(libPattern) : true;
            }
        };

        findFiles(directory, filter);
    }

    public static void main(String[] args) throws IOException {
        String javaHome = System.getProperty("java.home");
        findInDirectory(javaHome + "/bin", false);
        findInDirectory(javaHome + "/lib", true);
    }
}
