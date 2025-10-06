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
 *                          the highlight extends to the right from the caret
 *                          (e.g., when the text gets selected with Shift + Left Arrow).
 */
record JavaPreeditString(String text, int cursorBeginCodeUnit, int cursorEndCodeUnit) {
    public JavaPreeditString {
        Objects.requireNonNull(text, "text");
    }

    public static final JavaPreeditString EMPTY = new JavaPreeditString("", 0, 0);
    public static final JavaPreeditString EMPTY_NO_CARET = new JavaPreeditString("", -1, -1);

    public static JavaPreeditString fromWaylandPreeditString(
        final byte[] utf8Bytes,
        final int cursorBeginUtf8Byte,
        final int cursorEndUtf8Byte
    ) {
        // Java's UTF-8 -> UTF-16 conversion doesn't like trailing NUL codepoints, so let's trim them
        final int utf8BytesWithoutNulLength = Utilities.getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes);

        // cursorBeginUtf8Byte, cursorEndUtf8Byte normalized relatively to the valid values range.
        final int fixedCursorBeginUtf8Byte;
        final int fixedCursorEndUtf8Byte;
        if (cursorBeginUtf8Byte < 0 || cursorEndUtf8Byte < 0) {
            fixedCursorBeginUtf8Byte = fixedCursorEndUtf8Byte = -1;
        } else {
            // 0 <= cursorBeginUtf8Byte <= fixedCursorBeginUtf8Byte <= utf8BytesWithoutNulLength
            fixedCursorBeginUtf8Byte = Math.min(cursorBeginUtf8Byte, utf8BytesWithoutNulLength);
            // 0 <= cursorEndUtf8Byte <= fixedCursorEndUtf8Byte <= utf8BytesWithoutNulLength
            fixedCursorEndUtf8Byte = Math.min(cursorEndUtf8Byte, utf8BytesWithoutNulLength);
        }

        final var resultText = Utilities.utf8BytesToJavaString(utf8Bytes, 0, utf8BytesWithoutNulLength);

        if (fixedCursorBeginUtf8Byte < 0 || fixedCursorEndUtf8Byte < 0) {
            return new JavaPreeditString(resultText, -1, -1);
        }

        if (resultText == null) {
            assert(fixedCursorBeginUtf8Byte == 0);
            assert(fixedCursorEndUtf8Byte == 0);

            return JavaPreeditString.EMPTY;
        }

        final String javaPrefixBeforeCursorBegin = (fixedCursorBeginUtf8Byte == 0)
            ? ""
            : Utilities.utf8BytesToJavaString(utf8Bytes, 0, fixedCursorBeginUtf8Byte);

        final String javaPrefixBeforeCursorEnd = (fixedCursorEndUtf8Byte == 0)
            ? ""
            : Utilities.utf8BytesToJavaString(utf8Bytes, 0, fixedCursorEndUtf8Byte);

        return new JavaPreeditString(
            resultText,
            javaPrefixBeforeCursorBegin.length(),
            javaPrefixBeforeCursorEnd.length()
        );
    }
}
