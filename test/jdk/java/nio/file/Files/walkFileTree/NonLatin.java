/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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
 * @summary Tests that walkFileTree can find files in a directory with
 *          non-latin characters in the name (including ones from
 *          the supplementary plane)
 * @library /test/lib
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileVisitOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Vector;
import java.util.HashSet;

import jdk.test.lib.Asserts;

public class NonLatin {
    // Use non-Latin characters from basic (\u1889) and supplementary (\uD844\uDDD9) Unicode planes
    private static final String NON_LATIN_PATH_NAME = "ka-\u1889-supp-\uD844\uDDD9";

    public static void main(String args[]) throws Exception {
        final Path currentDirPath = Paths.get(".").toAbsolutePath();
        Path nonLatinPath = null;

        try {
            nonLatinPath = currentDirPath.resolve(NON_LATIN_PATH_NAME);
            Files.createDirectory(nonLatinPath);
            final Path underNonLatinPath = nonLatinPath.resolve("dir");
            Files.createDirectory(underNonLatinPath);
        } catch (java.nio.file.InvalidPathException e) {
            System.out.println("Cannot create the directory with the right name. Test is considered passed");
            return;
        }

        final Vector<String> visitedDirs = new Vector<String>();

        Files.walkFileTree(nonLatinPath, new HashSet<FileVisitOption>(), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                visitedDirs.add(dir.toString());
                return FileVisitResult.CONTINUE;
            }
        });

        boolean found = false;
        for (final String dirName : visitedDirs) {
            System.out.println("Found: " + dirName);
            found |= dirName.contains(NON_LATIN_PATH_NAME);
        }
        Asserts.assertTrue(found);
    }
}
