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

import java.nio.charset.StandardCharsets;


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

    static String utf8BytesToJavaString(final byte[] utf8Bytes) {
        if (utf8Bytes == null) {
            return "";
        }

        return utf8BytesToJavaString(
            utf8Bytes,
            0,
            // Java's UTF-8 -> UTF-16 conversion doesn't like trailing NUL codepoints, so let's trim them
            getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes)
        );
    }

    static String utf8BytesToJavaString(final byte[] utf8Bytes, final int offset, final int length) {
        return utf8Bytes == null ? "" : new String(utf8Bytes, offset, length, StandardCharsets.UTF_8);
    }
}
