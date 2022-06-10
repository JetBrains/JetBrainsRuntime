/*
 * Copyright 2022 JetBrains s.r.o.
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

/* @test
 * @summary Verifies that DirectoryStream's iterator works correctly in
 *          a multi-threaded environment.
 * @library ..
 * @run main/timeout=240 ParallelHeavy
 */
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ParallelHeavy {
    static final int RETRIES           = 42;
    static final int COUNT_TASKS       = 7;
    static final int COUNT_FILES       = 1024;
    static final int COUNT_DIRECTORIES = 1024;


    public static void main(String args[]) throws Exception {
        final Path testDir = Paths.get(System.getProperty("test.dir", "."));
        final Path dir = Files.createTempDirectory(testDir, "ParallelHeavy");

        populateDir(dir);

        for (int i = 0; i < RETRIES; i++) {
            runTest(dir, COUNT_DIRECTORIES + COUNT_FILES - i);
        }
    }

    static void advanceIteratorThenCancel(final DirectoryStream<Path> stream, final Iterator<Path> iterator, int closeAfterReading) {
        while (closeAfterReading-- > 0) {
            try {
                iterator.next();
            } catch (NoSuchElementException e) {
                System.out.println("NoSuchElementException, stream may have been closed, remaining to read "
                                   + closeAfterReading + ", thread " + Thread.currentThread().getId());
                break;
            }
            Thread.yield();
        }
        try {
            stream.close();
        } catch (IOException ignored) {}
    }

    static void runTest(final Path dir, int closeAfterReading) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            final Iterator<Path> streamIterator = stream.iterator();

            final ExecutorService pool = Executors.newCachedThreadPool();
            try {
                for (int i = 0; i < COUNT_TASKS - 1; i++) {
                    pool.submit(() -> advanceIteratorThenCancel(stream, streamIterator, closeAfterReading));
                }
                pool.submit(() -> advanceIteratorThenCancel(stream, streamIterator, COUNT_FILES));
                try {
                    pool.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}

            } finally {
                pool.shutdown();
            }
        } catch (DirectoryIteratorException ex) {
            throw ex.getCause();
        }
    }

    static void populateDir(final Path root) throws IOException {
        for (int i = 0; i < COUNT_DIRECTORIES; i++) {
            final Path newDir = root.resolve("directory-number-" + i);
            Files.createDirectory(newDir);
        }

        for (int i = 0; i < COUNT_FILES; i++) {
            final Path newFile = root.resolve("longer-file-name-number-" + i);
            Files.createFile(newFile);
        }
    }
}
