/*
 * Copyright 2025-2026 JetBrains s.r.o.
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

/**
 * This class represents the result of a conversion of a UTF-8 preedit string received in a
 * {@code zwp_text_input_v3::preedit_string} event to a Java UTF-16 string.
 * If {@link #cursorBeginCodeUnit} and/or {@link #cursorEndCodeUnit} point at UTF-16 surrogate pairs,
 *   they're guaranteed to point at the very beginning of them as long as {@link #fromWaylandPreeditString} is
 *   used to perform the conversion.
 * <p>
 * {@link #fromWaylandPreeditString} never returns {@code null}.
 * <p>
 * See the specification of {@code zwp_text_input_v3::preedit_string} event for more info about
 * cursor_begin, cursor_end values.
 *
 * @param text The preedit text string. Mustn't be {@code null} (use an empty string instead).
 * @param cursorBeginCodeUnit UTF-16 equivalent of {@code preedit_string.cursor_begin}.
 * @param cursorEndCodeUnit UTF-16 equivalent of {@code preedit_string.cursor_end}.
 *                          It's not explicitly stated in the protocol specification, but it seems to be a valid
 *                          situation when cursor_end < cursor_begin, which means
 *                          the highlight extends to the left from the caret.
 *
 * @see #fromWaylandPreeditString(byte[], int, int)
 */
public record JavaPreeditString(String text, int cursorBeginCodeUnit, int cursorEndCodeUnit) {
    public JavaPreeditString {
        Objects.requireNonNull(text, "text");
    }

    public static final JavaPreeditString EMPTY = new JavaPreeditString("", 0, 0);
    public static final JavaPreeditString EMPTY_NO_CARET = new JavaPreeditString("", -1, -1);


    /**
     * Converts a UTF-8 string and indices to it received in a {@code zwp_text_input_v3::preedit_string} event to their UTF-16 equivalents.
     * <p>
     * This is how data inconsistencies are handled:
     * <ul>
     *   <li>If {@code utf8Bytes} doesn't represent a valid UTF-8 string, a {@link CharacterCodingException} is thrown.</li>
     *   <li>Otherwise, if {@code cursorBeginUtf8Byte} points to a middle byte of a code point,
     *       it's considered as violating the protocol specification.
     *       In this case both {@link #cursorBeginCodeUnit} and {@link #cursorEndCodeUnit} of the resulting {@code JavaPreeditString}
     *       will point to the end of its {@link #text}.</li>
     *   <li>Also, if {@code cursorEndUtf8Byte} points to a middle byte of a code point,
     *       it's considered as violating the protocol specification.
     *       In this case {@link #cursorEndCodeUnit} of the resulting {@code JavaPreeditString}
     *       will be set equal to its {@link #cursorBeginCodeUnit}.</li>
     *   <li>If {@code cursorBeginUtf8Byte} or {@code cursorEndUtf8Byte} > {@code utf8Bytes.length},
     *       the corresponding {@link #cursorBeginCodeUnit} or {@link #cursorEndCodeUnit} of the resulting {@code JavaPreeditString}
     *       will be set to the length of its {@link #text}.</li>
     * </ul>
     *
     * @param utf8Bytes an UTF-8 encoded string
     * @param cursorBeginUtf8Byte {@code cursor_begin} parameter of the same {@code zwp_text_input_v3::preedit_string} event
     * @param cursorEndUtf8Byte {@code cursor_end} parameter of the same {@code zwp_text_input_v3::preedit_string} event
     *
     * @return an instance of {@code JavaPreeditString}. Never returns {@code null}
     *
     * @throws CharacterCodingException if {@code utf8Bytes} doesn't represent a valid UTF-8 string
     */
    // The method is tested via test/jdk/java/awt/wakefield/im/text_input_unstable_v3/WaylandPreeditStringToJavaConversionTest.java
    public static JavaPreeditString fromWaylandPreeditString(
        final byte[] utf8Bytes,
        final int cursorBeginUtf8Byte,
        final int cursorEndUtf8Byte
    ) throws CharacterCodingException {
        // The only Unicode code point that can contain zero byte(s) is U+000000.
        // It hardly makes sense to have it at the end of preedit/commit strings, so let's trim it.
        final int utf8BytesCorrectedLength = Utilities.getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes);

        // cursorBeginUtf8Byte, cursorEndUtf8Byte normalized relatively to the valid values range.
        final int fixedCursorBeginUtf8Byte;
        final int fixedCursorEndUtf8Byte;
        if (cursorBeginUtf8Byte < 0 || cursorEndUtf8Byte < 0) {
            fixedCursorBeginUtf8Byte = fixedCursorEndUtf8Byte = -1;
        } else {
            // 0 <= cursorBeginUtf8Byte <= fixedCursorBeginUtf8Byte <= utf8BytesCorrectedLength
            fixedCursorBeginUtf8Byte = Math.min(cursorBeginUtf8Byte, utf8BytesCorrectedLength);
            // 0 <= cursorEndUtf8Byte <= fixedCursorEndUtf8Byte <= utf8BytesCorrectedLength
            fixedCursorEndUtf8Byte = Math.min(cursorEndUtf8Byte, utf8BytesCorrectedLength);
        }

        if (utf8BytesCorrectedLength < 1) {
            return fixedCursorBeginUtf8Byte < 0 || fixedCursorEndUtf8Byte < 0
                   ? JavaPreeditString.EMPTY_NO_CARET
                   : JavaPreeditString.EMPTY;
        }

        final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                                                                 .onMalformedInput(CodingErrorAction.REPORT)
                                                                 // there can't be unmappable characters for this
                                                                 //   kind of conversion, so REPLACE is just in case
                                                                 .onUnmappableCharacter(CodingErrorAction.REPLACE);
        final CharBuffer decodingBuffer = CharBuffer.allocate(utf8BytesCorrectedLength + 1);
        final ByteBuffer utf8BytesBuffer = ByteBuffer.wrap(utf8Bytes, 0, utf8BytesCorrectedLength);
        CoderResult decodingResult;

        // The decoding will be performed in 3 sections:
        //   [0; decodingPoint1) + [decodingPoint1; decodingPoint2) + [decodingPoint2; utf8BytesCorrectedLength),
        //   where decodingPoint1, decodingPoint2 are the fixedCursor[Begin|End]Utf8Byte
        // This way we can translate the fixedCursor[Begin|End]Utf8Byte to their UTF-16 equivalents right while decoding.

        final int decodingPoint1 = Math.min(fixedCursorBeginUtf8Byte, fixedCursorEndUtf8Byte);
        final int decodingPoint2 = Math.max(fixedCursorBeginUtf8Byte, fixedCursorEndUtf8Byte);

        final int decodedPoint1;
        final int decodedPoint2;

        // [0; decodingPoint1)
        if (decodingPoint1 > 0) {
            utf8BytesBuffer.limit(decodingPoint1);

            decodingResult = utf8Decoder.decode(utf8BytesBuffer, decodingBuffer, false);
            if (decodingResult.isError() || decodingResult.isOverflow()) {
                decodingResult.throwException();
            }

            decodedPoint1 = decodingBuffer.position();
        } else {
            decodedPoint1 = decodingPoint1 < 0 ? -1 : 0;
        }

        // [decodingPoint1; decodingPoint2)
        if (decodingPoint2 > 0 && decodingPoint2 > decodingPoint1) {
            utf8BytesBuffer.limit(decodingPoint2);

            decodingResult = utf8Decoder.decode(utf8BytesBuffer, decodingBuffer, false);
            if (decodingResult.isError() || decodingResult.isOverflow()) {
                decodingResult.throwException();
            }

            decodedPoint2 = decodingBuffer.position();
        } else {
            decodedPoint2 = decodingPoint2 < 0 ? -1 : decodedPoint1;
        }

        // [decodingPoint2; utf8BytesCorrectedLength)
        {
            utf8BytesBuffer.limit(utf8BytesCorrectedLength);

            decodingResult = utf8Decoder.decode(utf8BytesBuffer, decodingBuffer, true);
            if (decodingResult.isError() || decodingResult.isOverflow()) {
                decodingResult.throwException();
            }
        }

        // last decoding step
        decodingResult = utf8Decoder.flush(decodingBuffer);
        if (decodingResult.isError() || decodingResult.isOverflow()) {
            decodingResult.throwException();
        }

        // From now on we know that utf8Bytes really represents a properly encoded UTF-8 string.

        // -1 <= decodedPoint1 <= decodedPoint2 <= utf8BytesCorrectedLength
        assert(decodedPoint1 >= -1);
        assert(decodedPoint2 >= decodedPoint1);
        assert(decodedPoint2 <= utf8BytesCorrectedLength);

        final String resultText = decodingBuffer.flip().toString();
        final int resultCursorBeginCodeUnit;
        final int resultCursorEndCodeUnit;

        if (fixedCursorBeginUtf8Byte < 0 || fixedCursorEndUtf8Byte < 0) {
            resultCursorBeginCodeUnit = resultCursorEndCodeUnit = -1;
        } else {
            // 0 <= decodedPoint1 <= decodedPoint2 <= utf8BytesCorrectedLength
            assert(decodedPoint1 >= 0);

            if (Utilities.isUtf8CharBoundary(fixedCursorBeginUtf8Byte, utf8Bytes, utf8BytesCorrectedLength)) {
                resultCursorBeginCodeUnit = fixedCursorBeginUtf8Byte < fixedCursorEndUtf8Byte
                                            ? decodedPoint1
                                            : decodedPoint2;

                if (Utilities.isUtf8CharBoundary(fixedCursorEndUtf8Byte, utf8Bytes, utf8BytesCorrectedLength)) {
                    resultCursorEndCodeUnit = fixedCursorBeginUtf8Byte < fixedCursorEndUtf8Byte
                                              ? decodedPoint2
                                              : decodedPoint1;
                } else {
                    resultCursorEndCodeUnit = resultCursorBeginCodeUnit;
                }
            } else {
                resultCursorBeginCodeUnit = resultCursorEndCodeUnit = resultText.length();
            }
        }

        return new JavaPreeditString(resultText, resultCursorBeginCodeUnit, resultCursorEndCodeUnit);
    }
}
