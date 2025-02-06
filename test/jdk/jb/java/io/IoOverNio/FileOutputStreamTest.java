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
 * @summary java.io.FileOutputStream uses java.nio.file inside.
 * @library testNio
 * @run junit/othervm
 *      -Djava.nio.file.spi.DefaultFileSystemProvider=testNio.ManglingFileSystemProvider
 *      -Djbr.java.io.use.nio=true
 *      FileOutputStreamTest
 */

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testNio.ManglingFileSystemProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FileOutputStreamTest {
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
    public void writeSingleByte() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.mangleFileContent = true;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write('l');
            fos.write('e');
            fos.write('e');
            fos.write('t');
        }

        ManglingFileSystemProvider.mangleFileContent = false;
        assertEquals("1337", Files.readString(file.toPath()));
    }

    @Test
    public void writeByteArray() throws Exception {
        File file = temporaryFolder.newFile();

        ManglingFileSystemProvider.mangleFileContent = true;
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write("hello".getBytes());
        }

        ManglingFileSystemProvider.mangleFileContent = false;
        assertEquals("h3110", Files.readString(file.toPath()));
    }

    @Test
    public void getChannel() throws Exception {
        File file = temporaryFolder.newFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            FileChannel channel = fos.getChannel();
            assertTrue(channel.getClass().getName(), channel instanceof testNio.ManglingFileChannel);
        }

    }
}
