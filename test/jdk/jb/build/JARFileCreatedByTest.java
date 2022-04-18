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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import static java.nio.file.FileVisitResult.CONTINUE;

/**
 * @test
 * @summary Regression test for JBR-4297. Verifies the attribute
 * <code>Created-By</code> in all jar files included into JRE this test is running on
 * @run main JARFileCreatedByTest
 */

public class JARFileCreatedByTest {
    private static final String expectedValue = "JetBrains";
    private static final String checkedAttr = "Created-By";
    private static boolean isFailed = false;

    public static void main(String[] args) throws IOException {
        Path jrePath = Paths.get(System.getProperty("java.home"));

        for (Path jarFile : new Finder(jrePath, "*.jar").files) {
            try {
                checkJARManifest(jarFile);
            } catch (RuntimeException e) {
                e.printStackTrace();
                isFailed = true;
            }
        }
        if (isFailed) throw new RuntimeException("JARFileCreatedByTest failed");
    }

    private static void checkJARManifest(Path jarFile) throws IOException {

        System.out.println("Checking JAR:" + jarFile);

        JarInputStream jarStream = new JarInputStream(new FileInputStream(jarFile.toFile()));
        Manifest mf = jarStream.getManifest();

        System.out.println("Checking attribute: " + checkedAttr);
        String createdByValue = mf.getMainAttributes().getValue(checkedAttr);

        if (!createdByValue.contains(expectedValue))
            throw new RuntimeException("\"" + checkedAttr + "\"" + " contains unexpected value \"" + createdByValue + "\"");
    }

    private static class Finder extends SimpleFileVisitor<Path> {

        private final PathMatcher matcher;
        ArrayList<Path> files = new ArrayList<>();

        Finder(Path rootDir, String pattern) throws IOException {
            matcher = FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern);
            Files.walkFileTree(rootDir, this);
        }

        void find(Path file) {
            Path name = file.getFileName();
            if (name != null && matcher.matches(name)) {
                files.add(file);
                System.out.println(file);
            }
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            find(file);
            return CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            find(dir);
            return CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            System.err.println(exc);
            return CONTINUE;
        }
    }
}
