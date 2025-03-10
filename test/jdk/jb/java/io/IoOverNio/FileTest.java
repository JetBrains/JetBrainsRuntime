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
 * @summary java.io.File uses java.nio.file inside.
 * @library testNio
 * @run junit/othervm
 *      -Djava.nio.file.spi.DefaultFileSystemProvider=testNio.ManglingFileSystemProvider
 *      -Djbr.java.io.use.nio=true
 *      FileTest
 */

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testNio.ManglingFileSystemProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static java.util.Arrays.sort;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void checkSystemProperties() {
        Objects.requireNonNull(System.getProperty("java.nio.file.spi.DefaultFileSystemProvider"));
    }

    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("win");

    private static void assumeNotWindows() {
        Assume.assumeFalse(IS_WINDOWS);
    }

    @Before
    @After
    public void resetFs() throws Exception {
        ManglingFileSystemProvider.resetTricks();
        try (var dirStream = Files.walk(temporaryFolder.getRoot().toPath())) {
            dirStream.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (!path.equals(temporaryFolder.getRoot().toPath())) {
                    try {
                        if (IS_WINDOWS) {
                            Files.setAttribute(path, "dos:readonly", false);
                        }
                        Files.delete(path);
                    } catch (IOException err) {
                        throw new UncheckedIOException(err);
                    }
                }
            });
        }
    }

    @Test
    public void getAbsolutePath() throws Exception {
        File file = temporaryFolder.newFile("hello world");
        assertNotEquals(file.getAbsolutePath(), ManglingFileSystemProvider.mangle(file.getAbsolutePath()));

        ManglingFileSystemProvider.manglePaths = true;
        assertEquals(file.getAbsolutePath(), ManglingFileSystemProvider.mangle(file.getAbsolutePath()));
    }

    @Test
    public void canRead() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");
        assertTrue(file.canRead());

        ManglingFileSystemProvider.denyAccessToEverything = true;
        assertFalse(file.canRead());
    }

    @Test
    public void canWrite() throws Exception {
        assumeNotWindows();

        File file = temporaryFolder.newFile("testFile.txt");
        assertTrue(file.canWrite());

        ManglingFileSystemProvider.denyAccessToEverything = true;
        assertFalse(file.canWrite());
    }

    @Test
    public void handlingEmptyPath() throws Exception {
        File file = new File("");

        // These checks fail
        assertFalse(file.exists());
        assertFalse(file.isDirectory());
        assertFalse(file.isFile());

        assertFalse(file.canRead());
        if (IS_WINDOWS) {
            assertTrue(file.setReadable(true));
        } else {
            assertFalse(file.setReadable(true));
        }
        assertFalse(file.canRead());

        assertFalse(file.canWrite());
        assertFalse(file.setWritable(true));
        assertFalse(file.canWrite());

        assertFalse(file.canExecute());
        if (IS_WINDOWS) {
            assertTrue(file.setExecutable(true));
        } else {
            assertFalse(file.setExecutable(true));
        }
        assertFalse(file.canExecute());

        assertFalse(file.setLastModified(1234567890L));

        assertEquals(null, file.list());

        if (IS_WINDOWS) {
            assertFalse(file.setReadOnly());
        }
    }

    @Test
    public void isDirectory() throws Exception {
        File dir = temporaryFolder.newFolder("testDir");
        File file = new File(dir, "testFile.txt");
        assertTrue(file.createNewFile());

        assertTrue(dir.isDirectory());
        assertFalse(file.isDirectory());

        ManglingFileSystemProvider.allFilesAreEmptyDirectories = true;
        assertTrue(dir.isDirectory());
        assertTrue(file.isDirectory());
    }

    @Test
    public void exists() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");
        File dir = temporaryFolder.newFolder("testDir");

        assertTrue(file.exists());
        assertTrue(dir.exists());

        assertFalse(new File(dir, "non-existing-file").exists());
        assertFalse(new File(file, "non-existing-file").exists());

        // Corner cases
        assertFalse(new File("").exists());
        assertTrue(new File(".").exists());
        assertTrue(new File("..").exists());

        if (IS_WINDOWS) {
            assertTrue(new File("C:\\..\\..").exists());
        } else {
            assertTrue(new File("/../..").exists());
        }

        boolean parentOfDirThatDoesNotExist = new File("dir-that-does-not-exist/..").exists();
        if (IS_WINDOWS) {
            assertTrue(parentOfDirThatDoesNotExist);
        } else {
            assertFalse(parentOfDirThatDoesNotExist);
        }
    }

    @Test
    public void isFile() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");
        File dir = temporaryFolder.newFolder("testDir");

        assertTrue(file.isFile());
        assertFalse(dir.isFile());

        ManglingFileSystemProvider.allFilesAreEmptyDirectories = true;
        assertFalse(file.isFile());
        assertFalse(dir.isFile());
    }

    @Test
    public void delete() throws Exception {
        File file1 = temporaryFolder.newFile("file1.txt");
        File file2 = temporaryFolder.newFile("file2.txt");
        assertTrue(file1.exists());
        assertTrue(file2.exists());

        assertTrue(file1.delete());
        assertFalse(file1.exists());

        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        assertFalse(file2.delete());
        assertTrue(file2.exists());
    }

    @Test
    public void list() throws Exception {
        File dir = temporaryFolder.newFolder("testDir");
        File file1 = new File(dir, "file1.txt");
        File file2 = new File(dir, "file2.txt");
        assertTrue(file1.createNewFile());
        assertTrue(file2.createNewFile());

        String[] files = dir.list();
        sort(files);
        assertArrayEquals(new String[]{"file1.txt", "file2.txt"}, files);

        ManglingFileSystemProvider.manglePaths = true;
        ManglingFileSystemProvider.addEliteToEveryDirectoryListing = true;
        files = dir.list();
        sort(files);
        assertArrayEquals(new String[]{"37337", "f1131.7x7", "f1132.7x7"}, files);
    }

    @Test
    public void mkdir() throws Exception {
        File dir1 = new File(temporaryFolder.getRoot(), "newDir1");
        assertTrue(dir1.mkdir());
        assertTrue(dir1.isDirectory());

        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        File dir2 = new File(temporaryFolder.getRoot(), "newDir2");
        assertFalse(dir2.mkdir());
        assertFalse(dir2.exists());
    }

    @Test
    public void renameTo() throws Exception {
        File file = temporaryFolder.newFile("originalName.txt");
        File renamedFile = new File(temporaryFolder.getRoot(), "newName.txt");
        assertTrue(file.exists());
        assertFalse(renamedFile.exists());

        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        File renamedFile2 = new File(temporaryFolder.getRoot(), "newName2.txt");
        assertFalse(renamedFile2.renameTo(renamedFile));
        assertFalse(renamedFile2.exists());
    }

    @Test
    public void setLastModified() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");

        // Beware that getting and setting mtime/atime/ctime is often unreliable.
        Assume.assumeTrue(file.setLastModified(1234567890L));
        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        assertFalse(file.setLastModified(123459999L));
    }

    @Test
    public void setReadOnly() throws Exception {
        File file1 = temporaryFolder.newFile("testFile1.txt");
        assertTrue(file1.canWrite());
        assertTrue(file1.setReadOnly());
        assertFalse(file1.canWrite());

        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        File file2 = temporaryFolder.newFile("testFile2.txt");
        assertFalse(file2.setReadOnly());
        assertTrue(file2.canWrite());
    }

    @Test
    public void setWritable() throws Exception {
        assumeNotWindows();

        assertTrue(temporaryFolder.newFile("testFile1.txt").setWritable(false));

        File file2 = temporaryFolder.newFile("testFile2.txt");
        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        assertFalse(file2.setWritable(false));
    }

    @Test
    public void setReadable() throws Exception {
        Assume.assumeFalse("setReadable(false) doesn't work for some reason on Windows, even for original java.io.File", IS_WINDOWS);

        File file1 = temporaryFolder.newFile("testFile1.txt");
        assertTrue(file1.setReadable(false));
        assertFalse(file1.canRead());

        File file2 = temporaryFolder.newFile("testFile2.txt");
        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        assertFalse(file2.setReadable(false));
        assertTrue(file2.canRead());
    }

    @Test
    public void setExecutable() throws Exception {
        assumeNotWindows();

        File file1 = temporaryFolder.newFile("testFile1.txt");
        Assume.assumeFalse(file1.canExecute());
        assertTrue(file1.setExecutable(true));
        assertTrue(file1.canExecute());

        File file2 = temporaryFolder.newFile("testFile2.txt");
        ManglingFileSystemProvider.prohibitFileTreeModifications = true;
        assertFalse(file2.setExecutable(true));
        assertFalse(file2.canExecute());
    }

    @Test
    public void listRoots() throws Exception {
        File[] roots1 = File.listRoots();
        String rootsString1 = Arrays.toString(roots1);
        assertFalse(rootsString1, rootsString1.contains("31337"));

        ManglingFileSystemProvider.addEliteToEveryDirectoryListing = true;
        File[] roots2 = File.listRoots();
        String rootsString2 = Arrays.toString(roots2);
        assertTrue(rootsString2, rootsString2.contains("31337"));
    }

    @Test
    public void getCanonicalPath() throws Exception {
        assumeNotWindows();

        // On macOS, the default temporary directory is a symlink.
        // The test won't work correctly without canonicalization here.
        File dir = temporaryFolder.newFolder().getCanonicalFile();

        File file = new File(dir, "file");
        Files.createFile(file.toPath());
        Files.createSymbolicLink(file.toPath().resolveSibling("123"), file.toPath());

        ManglingFileSystemProvider.manglePaths = true;
        ManglingFileSystemProvider.mangleOnlyFileName = true;
        assertEquals(new File(dir, "f113").toString(), new File(dir, "123").getCanonicalPath());
    }

    @Test
    public void normalizationInConstructor() throws Exception {
        assertEquals(".", new File(".").toString());
    }

    @Test
    public void unixSocketExists() throws Exception {
        assumeNotWindows();

        // Can't use `temporaryFolder` because it may have a long path,
        // but the length of a Unix socket path is limited in the Kernel.
        String shortTmpDir;
        {
            Process process = new ProcessBuilder("mktemp", "-d")
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start();
            try (BufferedReader br = new BufferedReader(process.inputReader())) {
                shortTmpDir = br.readLine();
            }
            assertEquals(0, process.waitFor());
        }
        try {
            File unixSocket = new File(shortTmpDir, "unix-socket");
            Process ncProcess = new ProcessBuilder("nc", "-lU", unixSocket.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            assertEquals(0, new ProcessBuilder(
                    "sh", "-c",
                    "I=50; while [ $I -gt 0 && test -S " + unixSocket + " ]; do sleep 0.1; I=$(expr $I - 1); done; test -S " + unixSocket)
                    .start()
                    .waitFor());

            try {
                assertTrue(unixSocket.exists());
            } finally {
                ncProcess.destroy();
            }
        } finally {
            new ProcessBuilder("rm", "-rf", shortTmpDir).start();
        }
    }

    @Test
    public void mkdirsWithDot() throws Exception {
        File dir = new File(temporaryFolder.getRoot(), "newDir1/.");
        assertTrue(dir.mkdirs());
        assertTrue(dir.isDirectory());
    }

    @Test
    public void canonicalizeTraverseBeyondRoot() throws Exception {
        File root = temporaryFolder.getRoot().toPath().getFileSystem().getRootDirectories().iterator().next().toFile();

        assertEquals(root, new File(root, "..").getCanonicalFile());
        assertEquals(new File(root, "123"), new File(root, "../123").getCanonicalFile());
    }

    @Test
    public void canonicalizeRelativePath() throws Exception {
        File cwd = new File(System.getProperty("user.dir")).getAbsoluteFile();

        assertEquals(cwd, new File("").getCanonicalFile());
        assertEquals(cwd, new File(".").getCanonicalFile());
        assertEquals(new File(cwd, "..").toPath().normalize().toFile(), new File("..").getCanonicalFile());
        assertEquals(new File(cwd, "abc"), new File("abc").getCanonicalFile());
        assertEquals(new File(cwd, "abc"), new File("abc/.").getCanonicalFile());
        assertEquals(cwd, new File("abc/..").getCanonicalFile());
    }

    @Test
    public void renameFileToAlreadyExistingFile() throws Exception {
        File file1 = temporaryFolder.newFile("testFile1.txt");
        try (var fos = new FileOutputStream(file1)) {
            fos.write("file1".getBytes());
        }

        File file2 = temporaryFolder.newFile("testFile2.txt");
        try (var fos = new FileOutputStream(file2)) {
            fos.write("file2".getBytes());
        }

        assertTrue(file1.exists());
        assertTrue(file2.exists());

        assertTrue(file1.renameTo(file2));

        assertFalse(file1.exists());
        assertTrue(file2.exists());

        try (var fis = Files.newInputStream(file2.toPath());
             var bis = new BufferedReader(new InputStreamReader(fis))) {
            String line = bis.readLine();
            assertEquals("file1", line);
        }
    }

    @Test
    public void testToCanonicalPathSymLinksAware() throws Exception {
        // getCanonicalFile capitalizes the drive letter on Windows.
        File rootDir = temporaryFolder.newFolder("root").getCanonicalFile();
        temporaryFolder.newFolder("root/dir1/dir2/dir3/dir4");
        String root = rootDir.getAbsolutePath();

        // non-recursive link
        Path link1 = new File(rootDir, "dir1/dir2_link").toPath();
        Path target1 = new File(rootDir, "dir1/dir2").toPath();
        try {
            Files.createSymbolicLink(link1, target1);
        } catch (AccessDeniedException ignored) {
            Assume.assumeTrue("Cannot create symbolic link", false);
        }
        // recursive links to a parent dir
        Path link = new File(rootDir, "dir1/dir1_link").toPath();
        Path target = new File(rootDir, "dir1").toPath();
        Files.createSymbolicLink(link, target);

        // links should NOT be resolved when ../ stays inside the linked path
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/./"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/dir3/../"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2/dir3", canonicalize(root + "/dir1/dir2_link/dir3/dir4/../"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/dir3/dir4/../../"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/../dir1/dir2_link/dir3/../"));

        // I.II) recursive links
        assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/./"));
        assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/dir2/../"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir1_link/dir2/dir3/../"));
        assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/dir2/dir3/../../"));
        assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/../dir1/dir1_link/dir2/../"));

        // II) links should be resolved is ../ escapes outside

        // II.I) non-recursive links
        assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir2_link/../"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/../dir2"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/../../dir1/dir2"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/dir3/../../dir2"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/dir2_link/dir3/../../../dir1/dir2"));
        assertEqualsReplacingSeparators(root + "/dir1/dir2", canonicalize(root + "/dir1/../dir1/dir2_link/../dir2"));

        if (IS_WINDOWS) {
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/../"));
            assertEqualsReplacingSeparators(root + "/dir1/dir1", canonicalize(root + "/dir1/dir1_link/../dir1"));
            assertEqualsReplacingSeparators(root + "/root/dir1", canonicalize(root + "/dir1/dir1_link/../../root/dir1"));
            assertEqualsReplacingSeparators(root + "/dir1/dir1", canonicalize(root + "/dir1/dir1_link/dir2/../../dir1"));
            assertEqualsReplacingSeparators(root + "/root/dir1", canonicalize(root + "/dir1/dir1_link/dir2/../../../root/dir1"));
            assertEqualsReplacingSeparators(root + "/dir1/dir1", canonicalize(root + "/dir1/../dir1/dir1_link/../dir1"));
        } else {
            assertEqualsReplacingSeparators(root, canonicalize(root + "/dir1/dir1_link/../"));
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/../dir1"));
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/../../root/dir1"));
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/dir2/../../dir1"));
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/dir1_link/dir2/../../../root/dir1"));
            assertEqualsReplacingSeparators(root + "/dir1", canonicalize(root + "/dir1/../dir1/dir1_link/../dir1"));
        }
    }

    private static String canonicalize(String path) throws IOException {
        return new File(path).getCanonicalFile().toString();
    }

    private static void assertEqualsReplacingSeparators(String expected, String actual) {
        assertEquals(expected.replace('\\', '/'), actual.replace('\\', '/'));
    }

    @Test
    public void testCreateNewFile() throws Exception {
        File rootDir = temporaryFolder.newFolder();

        File f = new File(rootDir, "hello.txt");
        assertFalse(f.exists());

        assertTrue(f.createNewFile());
        assertTrue(f.exists());

        assertFalse(f.createNewFile());
        assertTrue(f.exists());

        // Corner cases.
        assertFalse(rootDir.createNewFile());

        try {
            boolean ignored = new File("").createNewFile();
            fail("Expected IOException");
        } catch (IOException e) {
            // Nothing.
        }
        assertFalse(new File(".").createNewFile());
        assertFalse(new File("..").createNewFile());
    }

    @Test
    public void testDeletionOfReadOnlyFileOnWindows() throws Exception {
        Assume.assumeTrue(IS_WINDOWS);
        File dir = temporaryFolder.newFolder();
        File file = new File(dir, "testFile.txt");
        assertTrue(file.createNewFile());
        assertTrue(file.setReadOnly());

        assertTrue(file.delete());
        assertFalse(file.exists());
    }

    // TODO Test file size.

//    @Test
//    public void getTotalSpace() throws Exception {
//        throw new UnsupportedOperationException();
//    }
//
//    @Test
//    public void getFreeSpace() throws Exception {
//        throw new UnsupportedOperationException();
//    }
//
//    @Test
//    public void getUsableSpace() throws Exception {
//        throw new UnsupportedOperationException();
//    }
//
//    @Test
//    public void compareTo() throws Exception {
//        throw new UnsupportedOperationException();
//    }
}