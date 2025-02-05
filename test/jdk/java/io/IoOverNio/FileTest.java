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
 * @run junit/othervm
 *      -Djbr.java.io.use.nio=true
 *      FileTest
 */

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.util.Arrays.sort;
import static org.junit.Assert.*;

public class FileTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("win");

    private static void assumeNotWindows() {
        Assume.assumeFalse(IS_WINDOWS);
    }

    @Test
    public void canRead() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");
        assertTrue(file.canRead());
    }

    @Test
    public void canWrite() throws Exception {
        assumeNotWindows();

        File file = temporaryFolder.newFile("testFile.txt");
        assertTrue(file.canWrite());
    }

    @Test
    public void isDirectory() throws Exception {
        File dir = temporaryFolder.newFolder("testDir");
        File file = new File(dir, "testFile.txt");
        assertTrue(file.createNewFile());

        assertTrue(dir.isDirectory());
        assertFalse(file.isDirectory());
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

        assertFalse(new File("dir-that-does-not-exist/..").exists());
    }

    @Test
    public void isFile() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");
        File dir = temporaryFolder.newFolder("testDir");

        assertTrue(file.isFile());
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
    }

    @Test
    public void mkdir() throws Exception {
        File dir1 = new File(temporaryFolder.getRoot(), "newDir1");
        assertTrue(dir1.mkdir());
        assertTrue(dir1.isDirectory());
    }

    @Test
    public void renameTo() throws Exception {
        File file = temporaryFolder.newFile("originalName.txt");
        File renamedFile = new File(temporaryFolder.getRoot(), "newName.txt");
        assertTrue(file.exists());
        assertFalse(renamedFile.exists());
    }

    @Test
    public void setLastModified() throws Exception {
        File file = temporaryFolder.newFile("testFile.txt");

        // Beware that getting and setting mtime/atime/ctime is often unreliable.
        Assume.assumeTrue(file.setLastModified(1234567890L));
    }

    @Test
    public void setReadOnly() throws Exception {
        File file1 = temporaryFolder.newFile("testFile1.txt");
        assertTrue(file1.setReadOnly());
        assertFalse(file1.canWrite());
    }

    @Test
    public void setWritable() throws Exception {
        assumeNotWindows();

        assertTrue(temporaryFolder.newFile("testFile1.txt").setWritable(false));
    }

    @Test
    public void setReadable() throws Exception {
        File file1 = temporaryFolder.newFile("testFile1.txt");
        assertTrue(file1.setReadable(false));
        assertFalse(file1.canRead());
    }

    @Test
    public void setExecutable() throws Exception {
        assumeNotWindows();

        File file1 = temporaryFolder.newFile("testFile1.txt");
        Assume.assumeFalse(file1.canExecute());
        assertTrue(file1.setExecutable(true));
        assertTrue(file1.canExecute());
    }

    @Test
    public void listRoots() throws Exception {
        File[] roots1 = File.listRoots();
        String rootsString1 = Arrays.toString(roots1);
        assertFalse(rootsString1, rootsString1.contains("31337"));
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
                    "I=50; while [ $I -gt 0 && test -S " + unixSocket + " ]; do sleep 0.1; I=$(expr $I - 1); done; echo")
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
        assumeNotWindows();

        File rootDir = temporaryFolder.newFolder("root");
        temporaryFolder.newFolder("root/dir1/dir2/dir3/dir4");
        String root = rootDir.getAbsolutePath();

        // non-recursive link
        Path link1 = new File(rootDir, "dir1/dir2_link").toPath();
        Path target1 = new File(rootDir, "dir1/dir2").toPath();
        Files.createSymbolicLink(link1, target1);
        // recursive links to a parent dir
        Path link = new File(rootDir, "dir1/dir1_link").toPath();
        Path target = new File(rootDir, "dir1").toPath();
        Files.createSymbolicLink(link, target);

        // links should NOT be resolved when ../ stays inside the linked path
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/./").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/dir3/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2/dir3", new File(root + "/dir1/dir2_link/dir3/dir4/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/dir3/dir4/../../").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/../dir1/dir2_link/dir3/../").getCanonicalFile().toString());

        // I.II) recursive links
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/./").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/dir2/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir1_link/dir2/dir3/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/dir2/dir3/../../").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/../dir1/dir1_link/dir2/../").getCanonicalFile().toString());

        // II) links should be resolved is ../ escapes outside

        // II.I) non-recursive links
        assertEquals(root + "/dir1", new File(root + "/dir1/dir2_link/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/../dir2").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/../../dir1/dir2").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/dir3/../../dir2").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/dir2_link/dir3/../../../dir1/dir2").getCanonicalFile().toString());
        assertEquals(root + "/dir1/dir2", new File(root + "/dir1/../dir1/dir2_link/../dir2").getCanonicalFile().toString());

        assertEquals(root, new File(root + "/dir1/dir1_link/../").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/../dir1").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/../../root/dir1").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/dir2/../../dir1").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/dir1_link/dir2/../../../root/dir1").getCanonicalFile().toString());
        assertEquals(root + "/dir1", new File(root + "/dir1/../dir1/dir1_link/../dir1").getCanonicalFile().toString());
    }
}