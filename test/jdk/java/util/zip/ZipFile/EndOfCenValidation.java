/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8272746
 * @modules java.base/jdk.internal.util
 * @summary Verify that ZipFile rejects files with CEN sizes exceeding the implementation limit
 * @run testng/othervm EndOfCenValidation
 */

import jdk.internal.util.ArraysSupport;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.testng.Assert.*;

/**
 * This test augments {@link TestTooManyEntries}. It creates sparse ZIPs where
 * the CEN size is inflated to the desired value. This helps this test run
 * fast with much less resources.
 *
 * While the CEN in these files are zero-filled and the produced ZIPs are technically
 * invalid, the CEN is never actually read by ZipFile since it does
 * 'End of central directory record' (END header) validation before reading the CEN.
 */
public class EndOfCenValidation {

    // Zip files produced by this test
    public static final Path CEN_TOO_LARGE_ZIP = Path.of("cen-size-too-large.zip");
    public static final Path INVALID_CEN_SIZE = Path.of("invalid-zen-size.zip");
    public static final Path BAD_CEN_OFFSET_ZIP = Path.of("bad-cen-offset.zip");
    // Some ZipFile constants for manipulating the 'End of central directory record' (END header)
    private static final int ENDHDR = ZipFile.ENDHDR; // End of central directory record size
    private static final int ENDSIZ = ZipFile.ENDSIZ; // Offset of CEN size field within ENDHDR
    private static final int ENDOFF = ZipFile.ENDOFF; // Offset of CEN offset field within ENDHDR
    // Maximum allowed CEN size allowed by ZipFile
    private static int MAX_CEN_SIZE = ArraysSupport.SOFT_MAX_ARRAY_LENGTH;

    // Expected message when CEN size does not match file size
    private static final String INVALID_CEN_BAD_SIZE = "invalid END header (bad central directory size)";
    // Expected message when CEN offset is too large
    private static final String INVALID_CEN_BAD_OFFSET = "invalid END header (bad central directory offset)";
    // Expected message when CEN size is too large
    private static final String INVALID_CEN_SIZE_TOO_LARGE = "invalid END header (central directory size too large)";

    // A valid ZIP file, used as a template
    private byte[] zipBytes;

    /**
     * Create a valid ZIP file, used as a template
     * @throws IOException if an error occurs
     */
    @BeforeTest
    public void setup() throws IOException {
        zipBytes = templateZip();
    }

    /**
     * Delete big files after test, in case the file system did not support sparse files.
     * @throws IOException if an error occurs
     */
    @AfterTest
    public void cleanup() throws IOException {
        Files.deleteIfExists(CEN_TOO_LARGE_ZIP);
        Files.deleteIfExists(INVALID_CEN_SIZE);
        Files.deleteIfExists(BAD_CEN_OFFSET_ZIP);
    }

    /**
     * Validates that an 'End of central directory record' (END header) with a CEN
     * length exceeding {@link #MAX_CEN_SIZE} limit is rejected
     * @throws IOException if an error occurs
     */
    @Test
    public void shouldRejectTooLargeCenSize() throws IOException {
        int size = MAX_CEN_SIZE + 1;

        Path zip = zipWithModifiedEndRecord(size, true, 0, CEN_TOO_LARGE_ZIP);

        ZipException ex = expectThrows(ZipException.class, () -> {
            new ZipFile(zip.toFile());
        });

        assertEquals(ex.getMessage(), INVALID_CEN_SIZE_TOO_LARGE);
    }

    /**
     * Validate that an 'End of central directory record' (END header)
     * where the value of the CEN size field exceeds the position of
     * the END header is rejected.
     * @throws IOException if an error occurs
     */
    @Test
    public void shouldRejectInvalidCenSize() throws IOException {

        int size = MAX_CEN_SIZE;

        Path zip = zipWithModifiedEndRecord(size, false, 0, INVALID_CEN_SIZE);

        ZipException ex = expectThrows(ZipException.class, () -> {
            new ZipFile(zip.toFile());
        });

        assertEquals(ex.getMessage(), INVALID_CEN_BAD_SIZE);
    }

    /**
     * Validate that an 'End of central directory record' (the END header)
     * where the value of the CEN offset field is larger than the position
     * of the END header minus the CEN size is rejected
     * @throws IOException if an error occurs
     */
    @Test
    public void shouldRejectInvalidCenOffset() throws IOException {

        int size = MAX_CEN_SIZE;

        Path zip = zipWithModifiedEndRecord(size, true, 100, BAD_CEN_OFFSET_ZIP);

        ZipException ex = expectThrows(ZipException.class, () -> {
            new ZipFile(zip.toFile());
        });

        assertEquals(ex.getMessage(), INVALID_CEN_BAD_OFFSET);
    }

    /**
     * Create an ZIP file with a single entry, then modify the CEN size
     * in the 'End of central directory record' (END header)  to the given size.
     *
     * The CEN is optionally "inflated" with trailing zero bytes such that
     * its actual size matches the one stated in the END header.
     *
     * The CEN offset is optiontially adjusted by the given amount
     *
     * The resulting ZIP is technically not valid, but it does allow us
     * to test that large or invalid CEN sizes are rejected
     * @param cenSize the CEN size to put in the END record
     * @param inflateCen if true, zero-pad the CEN to the desired size
     * @param cenOffAdjust Adjust the CEN offset field of the END record with this amount
     * @throws IOException if an error occurs
     */
    private Path zipWithModifiedEndRecord(int cenSize,
                                          boolean inflateCen,
                                          int cenOffAdjust,
                                          Path zip) throws IOException {

        // A byte buffer for reading the END
        ByteBuffer buffer = ByteBuffer.wrap(zipBytes.clone()).order(ByteOrder.LITTLE_ENDIAN);

        // Offset of the END header
        int endOffset = buffer.limit() - ENDHDR;

        // Modify the CEN size
        int sizeOffset = endOffset + ENDSIZ;
        int currentCenSize = buffer.getInt(sizeOffset);
        buffer.putInt(sizeOffset, cenSize);

        // Optionally modify the CEN offset
        if (cenOffAdjust != 0) {
            int offOffset = endOffset + ENDOFF;
            int currentCenOff = buffer.getInt(offOffset);
            buffer.putInt(offOffset, currentCenOff + cenOffAdjust);
        }

        // When creating a sparse file, the file must not already exit
        Files.deleteIfExists(zip);

        // Open a FileChannel for writing a sparse file
        EnumSet<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE);

        try (FileChannel channel = FileChannel.open(zip, options)) {

            // Write everything up to END
            channel.write(buffer.slice(0, buffer.limit() - ENDHDR));

            if (inflateCen) {
                // Inject "empty bytes" to make the actual CEN size match the END
                int injectBytes = cenSize - currentCenSize;
                channel.position(channel.position() + injectBytes);
            }
            // Write the modified END
            channel.write(buffer.slice(buffer.limit() - ENDHDR, ENDHDR));
        }
        return zip;
    }

    /**
     * Produce a byte array of a ZIP with a single entry
     *
     * @throws IOException if an error occurs
     */
    private byte[] templateZip() throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (ZipOutputStream zo = new ZipOutputStream(bout)) {
            ZipEntry entry = new ZipEntry("duke.txt");
            zo.putNextEntry(entry);
            zo.write("duke".getBytes(StandardCharsets.UTF_8));
        }
        return bout.toByteArray();
    }
}
