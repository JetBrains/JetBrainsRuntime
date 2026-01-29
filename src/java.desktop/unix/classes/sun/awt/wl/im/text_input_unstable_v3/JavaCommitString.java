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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

record JavaCommitString(String text) {
    public JavaCommitString {
        Objects.requireNonNull(text, "text");
    }

    public static final JavaCommitString EMPTY = new JavaCommitString("");

    /**
     * Converts a UTF-8 string received in a {@code zwp_text_input_v3::commit_string} event to its UTF-16 equivalent.
     *
     * @return an instance of {@code JavaCommitString}. Never returns {@code null}.
     * @throws CharacterCodingException if {@code utf8Bytes} doesn't represent a valid UTF-8 string.
     */
    public static JavaCommitString fromWaylandCommitString(byte[] utf8Bytes) throws CharacterCodingException {
        // The only Unicode code point that can contain zero byte(s) is U+000000.
        // It hardly makes sense to have it at the end of preedit/commit strings, so let's trim it.
        final int utf8BytesCorrectedLength = Utilities.getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes);

        if (utf8BytesCorrectedLength < 1) {
            return JavaCommitString.EMPTY;
        }

        final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                                                                 .onMalformedInput(CodingErrorAction.REPORT)
                                                                 // there can't be unmappable characters for this
                                                                 //   kind of conversion, so REPLACE is just in case
                                                                 .onUnmappableCharacter(CodingErrorAction.REPLACE);
        final CharBuffer decodingBuffer = CharBuffer.allocate(utf8BytesCorrectedLength + 1);
        final ByteBuffer utf8BytesBuffer = ByteBuffer.wrap(utf8Bytes, 0, utf8BytesCorrectedLength);

        CoderResult decodingResult = utf8Decoder.decode(utf8BytesBuffer, decodingBuffer, true);
        if (decodingResult.isError() || decodingResult.isOverflow()) {
            decodingResult.throwException();
        }

        decodingResult = utf8Decoder.flush(decodingBuffer);
        if (decodingResult.isError() || decodingResult.isOverflow()) {
            decodingResult.throwException();
        }

        return new JavaCommitString(decodingBuffer.flip().toString());
    }
}
