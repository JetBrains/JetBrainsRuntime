/*
 * Copyright 2025 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.awt.wl.im.text_input_unstable_v3;


interface Utilities {
    static int getLengthOfUtf8BytesWithoutTrailingNULs(final byte[] utf8Bytes) {
        int lastNonNulIndex = (utf8Bytes == null) ? -1 : utf8Bytes.length - 1;
        for (; lastNonNulIndex >= 0; --lastNonNulIndex) {
            if (utf8Bytes[lastNonNulIndex] != 0) {
                break;
            }
        }

        return (lastNonNulIndex < 0) ? 0 : lastNonNulIndex + 1;
    }

    /**
     * Checks that {@code index}-th byte is the first byte in a UTF-8 code point sequence or the end of the string.
     *
     * @param index index of the byte in {@code utf8StrBytes} to check
     * @param utf8StrBytes byte array representing a correctly encoded UTF-8 string
     * @param utf8StrBytesLength if non-negative, will be considered as the array length instead of {@code utf8StrBytes.length}
     * @return {@code true} if either {@code index} points to the end of the string or to a first byte of a code point
     */
    static boolean isUtf8CharBoundary(final int index, final byte[] utf8StrBytes, int utf8StrBytesLength) {
        utf8StrBytesLength = utf8StrBytesLength < 0 ? utf8StrBytes.length : utf8StrBytesLength;

        if (utf8StrBytesLength > utf8StrBytes.length) {
            throw new ArrayIndexOutOfBoundsException("utf8StrBytesLength");
        }
        if (index < 0 || index > utf8StrBytesLength) {
            throw new ArrayIndexOutOfBoundsException("index");
        }

        if (index == utf8StrBytesLength) {
            return true;
        }

        final byte utf8Byte = utf8StrBytes[index];

        // In a valid UTF-8 string, a byte is the first byte of an encoded code point if and only if
        //   its binary representation does NOT start with 10, i.e. does NOT match 0b10......

        final int utf8ByteUnsigned = Byte.toUnsignedInt(utf8Byte);
        return ((utf8ByteUnsigned & 0b1100_0000) != 0b1000_0000);
    }
}
