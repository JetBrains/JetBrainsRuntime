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
 * @summary java.io.FileInputStream uses java.nio.file inside.
 * @library testNio
 * @run junit/othervm
 *      -Djava.nio.file.spi.DefaultFileSystemProvider=testNio.ManglingFileSystemProvider
 *      -Djbr.java.io.use.nio=true
 *      FileInputStreamTest
 */

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testNio.ManglingFileSystemProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class FileInputStreamTest {
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

    @Test
    public void readSingleByte() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "hello world");

        ManglingFileSystemProvider.mangleFileContent = true;
        try (FileInputStream fis = new FileInputStream(file)) {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int b = fis.read();
                if (b == -1) {
                    break;
                }
                sb.append((char) b);
            }
            assertEquals("h3110 w0r1d", sb.toString());
        }
    }

    @Test
    public void readByteArray() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "hello world");

        ManglingFileSystemProvider.mangleFileContent = true;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[12345];
            int read = fis.read(buf);
            assertNotEquals(-1, read);
            String content = new String(buf, 0, read, StandardCharsets.UTF_8);
            assertEquals("h3110 w0r1d", content);
        }
    }

    @Test
    public void readAllBytes() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "hello world ");

        ManglingFileSystemProvider.mangleFileContent = true;
        ManglingFileSystemProvider.readExtraFileContent = true;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = fis.readAllBytes();
            String content = new String(buf, StandardCharsets.UTF_8);
            assertEquals("h3110 w0r1d " + ManglingFileSystemProvider.extraContent, content);
        }
    }

    @Test
    public void transferTo() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "hello world");

        ManglingFileSystemProvider.mangleFileContent = true;
        try (FileInputStream fis = new FileInputStream(file)) {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                fis.transferTo(baos);
                assertEquals("h3110 w0r1d", baos.toString(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void skip() throws Exception {
        File file = temporaryFolder.newFile();
        Files.writeString(file.toPath(), "especially lovely greet");

        ManglingFileSystemProvider.mangleFileContent = true;
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            sb.append((char) fis.read());
            assertEquals(10, fis.skip(10));
            sb.append((char) fis.read());
            assertEquals(8, fis.skip(8));
            sb.append(new String(fis.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("31337", sb.toString());
    }

    @Test
    public void available() throws Exception {
        File file = temporaryFolder.newFile();
        try (FileInputStream fis = new FileInputStream(file)) {
            assertEquals(0, fis.available());
        }

        ManglingFileSystemProvider.readExtraFileContent = true;
        try (FileInputStream fis = new FileInputStream(file)) {
            assertEquals(ManglingFileSystemProvider.extraContent.length(), fis.available());
            byte[] bytes = fis.readAllBytes();
            assertEquals(ManglingFileSystemProvider.extraContent, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void getChannel() throws Exception {
        File file = temporaryFolder.newFile();
        try (FileInputStream fis = new FileInputStream(file)) {
            FileChannel channel = fis.getChannel();
            assertTrue(channel.getClass().getName(), channel instanceof testNio.ManglingFileChannel);
        }
    }
}
