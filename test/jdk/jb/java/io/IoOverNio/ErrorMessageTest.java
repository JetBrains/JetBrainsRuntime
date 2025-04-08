/*
 * Copyright 2025 JetBrains s.r.o.
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
 * @summary java.io.File uses java.nio.file inside, special test for error messages consistency.
 * @library testNio
 * @run junit/othervm
 *      -Djava.nio.file.spi.DefaultFileSystemProvider=testNio.ManglingFileSystemProvider
 *      -Djbr.java.io.use.nio=true
 *      ErrorMessageTest
 */

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testNio.ManglingFileSystemProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.util.Objects;

import static org.junit.Assert.fail;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class ErrorMessageTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void checkSystemProperties() {
        Objects.requireNonNull(System.getProperty("java.nio.file.spi.DefaultFileSystemProvider"));
    }

    @Before
    @After
    public void resetFs() {
        ManglingFileSystemProvider.resetTricks();
    }

    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("win");
    public static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().startsWith("mac");

    @Test
    public void noSuchFileOrDirectory() throws Exception {
        File nonExistentEntity = temporaryFolder.newFile();
        nonExistentEntity.delete();

        File entityInNonExistentDir = new File(nonExistentEntity, "foo");

        test(
                new FileNotFoundException(nonExistentEntity + (IS_WINDOWS ? " (The system cannot find the file specified)" : " (No such file or directory)")),
                () -> new FileInputStream(nonExistentEntity).close()
        );

        test(
                new FileNotFoundException(nonExistentEntity + (IS_WINDOWS ? " (The system cannot find the file specified)" : " (No such file or directory)")),
                () -> new RandomAccessFile(nonExistentEntity, "r").close()
        );

        test(
                new IOException(IS_WINDOWS ? "The system cannot find the path specified" : "No such file or directory"),
                entityInNonExistentDir::createNewFile
        );

        test(
                new IOException(IS_WINDOWS ? "The system cannot find the path specified" : "No such file or directory"),
                () -> File.createTempFile("foo", "bar", nonExistentEntity)
        );
    }

    @Test
    public void randomAccessFileWrongOperations() throws Exception {
        File f = temporaryFolder.newFile();
        try (RandomAccessFile readOnly = new RandomAccessFile(f, "r")) {
            test(
                    new IOException("Bad file descriptor"),
                    () -> readOnly.write(1)
            );
            test(
                    new IOException("Bad file descriptor"),
                    () -> readOnly.write(new byte[1])
            );
        }
    }

    @Test
    public void accessDenied() throws Exception {
        File f;
        if (IS_WINDOWS) {
            f = new File("C:\\Windows\\System32\\config\\SAM");
            try {
                Files.list(f.toPath().getParent()).close();
                Assume.assumeTrue(false);
            } catch (AccessDeniedException _) {
                // Nothing.
            }
        } else if (IS_MAC) {
            Assume.assumeFalse(System.getProperty("user.name").equals("root"));
            f = new File("/private/var/audit/current");
        } else {
            Assume.assumeFalse(System.getProperty("user.name").equals("root"));
            f = new File("/etc/shadow");
        }

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (Access is denied)" : " (Permission denied)")),
                () -> new FileInputStream(f).close()
        );

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (Access is denied)" : " (Permission denied)")),
                () -> new FileOutputStream(f).close()
        );

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (Access is denied)" : " (Permission denied)")),
                () -> new RandomAccessFile(f, "r").close()
        );

        if (IS_MAC) {
            test(
                    new IOException("Permission denied"),
                    f::createNewFile
            );
        }
        test(
                new IOException(IS_WINDOWS ? "The system cannot find the path specified" : IS_MAC ? "Permission denied" : "Not a directory"),
                () -> File.createTempFile("foo", "bar", f)
        );
    }

    @Test
    public void useDirectoryAsFile() throws Exception {
        File dir = temporaryFolder.newFolder();

        test(
                new FileNotFoundException(dir + (IS_WINDOWS ? " (Access is denied)" : " (Is a directory)")),
                () -> new FileInputStream(dir).close()
        );

        test(
                new FileNotFoundException(dir + (IS_WINDOWS ? " (Access is denied)" : " (Is a directory)")),
                () -> new FileOutputStream(dir).close()
        );

        test(
                new FileNotFoundException(dir + (IS_WINDOWS ? " (Access is denied)" : " (Is a directory)")),
                () -> new RandomAccessFile(dir, "r").close()
        );
    }

    @Test
    public void useFileAsDirectory() throws Exception {
        File f = new File(temporaryFolder.newFile(), "foo");

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (The system cannot find the path specified)" : " (Not a directory)")),
                () -> new FileInputStream(f).close()
        );

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (The system cannot find the path specified)" : " (Not a directory)")),
                () -> new FileOutputStream(f).close()
        );

        test(
                new FileNotFoundException(f + (IS_WINDOWS ? " (The system cannot find the path specified)" : " (Not a directory)")),
                () -> new RandomAccessFile(f, "r").close()
        );

        test(
                new IOException((IS_WINDOWS ? "The system cannot find the path specified" : "Not a directory")),
                f::createNewFile
        );

        test(
                new IOException((IS_WINDOWS ? "The system cannot find the path specified" : "Not a directory")),
                () -> File.createTempFile("foo", "bar", f)
        );
    }

    @Test
    public void symlinkLoop() throws Exception {
        Assume.assumeFalse("This test doesn't support support symlinks on Windows", IS_WINDOWS);

        File f = new File(temporaryFolder.newFolder(), "tricky_link");
        Files.createSymbolicLink(f.toPath(), f.toPath());

        test(
                new FileNotFoundException(f + " (Too many levels of symbolic links)"),
                () -> new FileInputStream(f).close()
        );

        test(
                new FileNotFoundException(f + " (Too many levels of symbolic links)"),
                () -> new FileOutputStream(f).close()
        );

        test(
                new FileNotFoundException(f + " (Too many levels of symbolic links)"),
                () -> new RandomAccessFile(f, "r").close()
        );

        test(
                new IOException("Too many levels of symbolic links"),
                () -> File.createTempFile("foo", "bar", f)
        );
    }

    @Test
    public void createNewFile() throws Exception {
        test(
                new IOException(IS_WINDOWS ? "The system cannot find the path specified" : "No such file or directory"),
                () -> new File("").createNewFile()
        );
    }

    // TODO Try to test operations of FileInputStream, FileOutputStream, etc.

    private static void test(Exception expectedException, TestRunnable fn) {
        test(expectedException, () -> {
            fn.run();
            return Void.TYPE;
        });
    }

    private static <T> void test(Exception expectedException, TestComputable<T> fn) {
        final T result;
        try {
            result = fn.run();
        } catch (Exception err) {
            if (err.getClass().equals(expectedException.getClass()) && Objects.equals(err.getMessage(), expectedException.getMessage())) {
                return;
            }
            AssertionError assertionError = new AssertionError("Another exception was expected", err);
            assertionError.addSuppressed(expectedException);
            throw assertionError;
        }
        fail("Expected an exception but got a result: " + result);
    }

    @FunctionalInterface
    private interface TestRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface TestComputable<T> {
        T run() throws Exception;
    }
}
