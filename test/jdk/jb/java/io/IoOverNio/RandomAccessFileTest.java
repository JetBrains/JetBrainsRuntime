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
 * @summary java.io.RandomAccessFileTest uses java.nio.file inside.
 * @library testNio
 * @run junit/othervm
 *      -Djava.nio.file.spi.DefaultFileSystemProvider=testNio.ManglingFileSystemProvider
 *      -Djbr.java.io.use.nio=true
 *      RandomAccessFileTest
 */

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testNio.ManglingFileSystemProvider;

import java.io.EOFException;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


public class RandomAccessFileTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void checkSystemProperties() {
        Objects.requireNonNull(System.getProperty("java.nio.file.spi.DefaultFileSystemProvider"));
    }

    private static void assumeNotWindows() {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase().startsWith("win"));
    }

    @Before
    @After
    public void resetFs() {
        ManglingFileSystemProvider.resetTricks();
    }

    @Test
    public void usesNioForNonExistingFiles() throws Exception {
        File nonExistingFile = new File(temporaryFolder.getRoot(), "non-existing-file");
        assertFalse(Files.exists(nonExistingFile.toPath()));
        ManglingFileSystemProvider.mangleFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(nonExistingFile, "rw")) {
            byte[] buffer = "hello".getBytes();
            rac.write(buffer);
            rac.seek(0);
            rac.readFully(buffer);
            assertEquals("h3110", new String(buffer));
        }
    }

    @Test
    public void getChannel() throws Exception {
        File file = temporaryFolder.newFile();
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            FileChannel channel = rac.getChannel();
            assertTrue(channel.getClass().getName(), channel instanceof testNio.ManglingFileChannel);
        }
        try (RandomAccessFile rac = new RandomAccessFile(file, "rw")) {
            FileChannel channel = rac.getChannel();
            assertTrue(channel.getClass().getName(), channel instanceof testNio.ManglingFileChannel);
        }
    }

    @Test
    public void getFilePointer() throws Exception {
        File file = temporaryFolder.newFile();
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            assertEquals(0, rac.getFilePointer());
        }

        ManglingFileSystemProvider.readExtraFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            rac.readLine();
            assertEquals(ManglingFileSystemProvider.extraContent.length(), rac.getFilePointer());
        }
    }

    @Test
    public void length() throws Exception {
        File file = temporaryFolder.newFile();
        String content = "hello";
        Files.writeString(file.toPath(), content);
        ManglingFileSystemProvider.readExtraFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
           assertEquals(content.length() + ManglingFileSystemProvider.extraContent.length(), rac.length());
        }
    }

    @Test
    public void read() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.readExtraFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            String extraContent = ManglingFileSystemProvider.extraContent;
            for (int i = 0; i < extraContent.length(); i++) {
                assertEquals((int) extraContent.charAt(i), rac.read());
            }
            assertEquals(-1, rac.read());
        }
    }

    @Test
    public void readFully() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "adapters everywhere");

        ManglingFileSystemProvider.mangleFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            byte[] data = new byte[10];
            rac.readFully(data);
            assertEquals("4d4p73r5 3", new String(data));

            Arrays.fill(data, (byte)' ');
            try {
                rac.readFully(data);
                fail("An error should have been thrown but wasn't");
            } catch (EOFException _) {
                // Nothing.
            }
            assertEquals("v3rywh3r3 ", new String(data));
        }
    }

    @Test
    public void seek() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.readExtraFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "r")) {
            String extraContent = ManglingFileSystemProvider.extraContent;
            for (int i = extraContent.length() - 1; i >= 0; i--) {
                rac.seek(i);
                assertEquals(extraContent.charAt(i), (char) rac.readByte());
            }
        }
    }

    @Test
    public void setLength() throws Exception {
        String extraContent = ManglingFileSystemProvider.extraContent;
        assertTrue(extraContent.length() > 2);
        assertTrue(extraContent.length() < 11);

        File file = temporaryFolder.newFile();
        String content = "hello";
        Files.writeString(file.toPath(), content);

        ManglingFileSystemProvider.mangleFileContent = true;
        ManglingFileSystemProvider.readExtraFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "rw")) {
            assertEquals(content.length() + extraContent.length(), rac.length());
            rac.setLength(content.length() + 2);
            assertEquals(content.length() + 2, rac.length());
            rac.seek(0);
            assertEquals("h3110" + extraContent.substring(0, 2), rac.readLine());
        }

        ManglingFileSystemProvider.readExtraFileContent = false;
        Files.writeString(file.toPath(), content);
        try (RandomAccessFile rac = new RandomAccessFile(file, "rw")) {
            assertEquals(content.length(), rac.length());
            rac.setLength(11);
            assertEquals(11, rac.length());

            rac.seek(0);
            byte[] buffer = new byte[1234];
            try {
                rac.readFully(buffer);
                fail("EOFException wasn't thrown");
            } catch (EOFException ignored) {
                // Expected behavior.
            }
            assertEquals("h3110\0\0\0\0\0\0", new String(buffer, 0, 11));

            // setLength should keep the offset.
            rac.seek(0);
            assertEquals('h', rac.readByte());
            rac.setLength(17);
            assertEquals('3', rac.readByte());
            rac.setLength(3);
            assertEquals('1', rac.readByte());
            try {
                rac.readByte();
                fail("EOFException wasn't thrown");
            } catch (EOFException ignored) {
                // Expected behavior.
            }
        }
    }

    @Test
    public void writeSingleByte() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.mangleFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "rw")) {
            rac.write('f');
            rac.write('o');
            rac.write('o');
            rac.write('b');
            rac.write('a');
            rac.write('r');
        }

        ManglingFileSystemProvider.mangleFileContent = false;
        assertEquals("f00b4r", Files.readString(file.toPath()));
    }

    @Test
    public void writeBytes() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.mangleFileContent = true;
        try (RandomAccessFile rac = new RandomAccessFile(file, "rw")) {
            rac.writeBytes("herpderp");
        }

        ManglingFileSystemProvider.mangleFileContent = false;
        assertEquals("h3rpd3rp", Files.readString(file.toPath()));
    }
}
