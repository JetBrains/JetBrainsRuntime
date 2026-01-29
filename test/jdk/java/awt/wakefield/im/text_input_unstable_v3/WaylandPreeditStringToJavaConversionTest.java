/*
 * Copyright 2026 JetBrains s.r.o.
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

import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;
import sun.awt.wl.im.text_input_unstable_v3.JavaPreeditString;

import java.nio.charset.CharacterCodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.util.Map.entry;
import static org.testng.Assert.assertThrows;

/*
 * @test
 * @summary Covers the method fromWaylandPreeditString
 * @key i18n
 * @requires (os.family == "linux")
 * @modules java.desktop/sun.awt.wl.im.text_input_unstable_v3:+open
 * @run testng/othervm -ea -esa WaylandPreeditStringToJavaConversionTest
 */
public class WaylandPreeditStringToJavaConversionTest {

    @Test
    public void testEmptyPreeditString() {
        testConversionResultValuesImpl(
            new byte[0],
            "",

            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                // Section of valid data (not violating the protocol)

                entry(pair(0, 0),   pair(0, 0)),

                entry(pair(-1, -1), pair(-1, -1)),

                // Section of invalid data that we expect the implementation to be robust and sensible for though

                // ("", ..., -1) , ("", -1, ...) -> ("", -1, -1)
                entry(pair(-2, -2), pair(-1, -1)),
                entry(pair(-2, -1), pair(-1, -1)),
                entry(pair(-2, 0),  pair(-1, -1)),
                entry(pair(-2, 1),  pair(-1, -1)),
                entry(pair(-2, 2),  pair(-1, -1)),
                entry(pair(-1, -2), pair(-1, -1)),
                entry(pair(-1, 0),  pair(-1, -1)),
                entry(pair(-1, 1),  pair(-1, -1)),
                entry(pair(-1, 2),  pair(-1, -1)),
                entry(pair(0, -2),  pair(-1, -1)),
                entry(pair(0, -1),  pair(-1, -1)),
                entry(pair(1, -2),  pair(-1, -1)),
                entry(pair(1, -1),  pair(-1, -1)),
                entry(pair(2, -2),  pair(-1, -1)),
                entry(pair(2, -1),  pair(-1, -1)),

                // Non-negative but out-of-range cursor boundaries
                entry(pair(2, 2),   pair(0, 0)),
                entry(pair(1, 2),   pair(0, 0)),
                entry(pair(0, 2),   pair(0, 0)),
                entry(pair(2, 1),   pair(0, 0)),
                entry(pair(1, 1),   pair(0, 0)),
                entry(pair(0, 1),   pair(0, 0)),
                entry(pair(2, 0),   pair(0, 0)),
                entry(pair(1, 0),   pair(0, 0))
            )
        );

        // Exactly the same as above but null instead of the empty byte array
        testConversionResultValuesImpl(
            null,
            "",

            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                // Section of valid data (not violating the protocol)

                entry(pair(0, 0),   pair(0, 0)),

                entry(pair(-1, -1), pair(-1, -1)),

                // Section of invalid data that we expect the implementation to be robust and sensible for though

                // ("", ..., -1) , ("", -1, ...) -> ("", -1, -1)
                entry(pair(-2, -2), pair(-1, -1)),
                entry(pair(-2, -1), pair(-1, -1)),
                entry(pair(-2, 0),  pair(-1, -1)),
                entry(pair(-2, 1),  pair(-1, -1)),
                entry(pair(-2, 2),  pair(-1, -1)),
                entry(pair(-1, -2), pair(-1, -1)),
                entry(pair(-1, 0),  pair(-1, -1)),
                entry(pair(-1, 1),  pair(-1, -1)),
                entry(pair(-1, 2),  pair(-1, -1)),
                entry(pair(0, -2),  pair(-1, -1)),
                entry(pair(0, -1),  pair(-1, -1)),
                entry(pair(1, -2),  pair(-1, -1)),
                entry(pair(1, -1),  pair(-1, -1)),
                entry(pair(2, -2),  pair(-1, -1)),
                entry(pair(2, -1),  pair(-1, -1)),

                // Non-negative but out-of-range cursor boundaries
                entry(pair(2, 2),   pair(0, 0)),
                entry(pair(1, 2),   pair(0, 0)),
                entry(pair(0, 2),   pair(0, 0)),
                entry(pair(2, 1),   pair(0, 0)),
                entry(pair(1, 1),   pair(0, 0)),
                entry(pair(0, 1),   pair(0, 0)),
                entry(pair(2, 0),   pair(0, 0)),
                entry(pair(1, 0),   pair(0, 0))
            )
        );
    }

    @Test
    public void testOneCodeUnitPreeditString() {
        testConversionResultValuesImpl(
            new byte[]{ (byte)0x61 },
            "a",

            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                // Section of valid data (not violating the protocol)

                entry(pair(0, 0),   pair(0, 0)),

                entry(pair(-1, -1), pair(-1, -1)),

                entry(pair(0, 1),   pair(0, 1)),
                entry(pair(1, 0),   pair(1, 0)),
                entry(pair(1, 1),   pair(1, 1)),

                // Section of invalid data that we expect the implementation to be robust and sensible for though

                // ("a", ..., -1) , ("a", -1, ...) -> ("a", -1, -1)
                entry(pair(-2, -2), pair(-1, -1)),
                entry(pair(-2, -1), pair(-1, -1)),
                entry(pair(-2, 0),  pair(-1, -1)),
                entry(pair(-2, 1),  pair(-1, -1)),
                entry(pair(-2, 2),  pair(-1, -1)),
                entry(pair(-1, -2), pair(-1, -1)),
                entry(pair(-1, 0),  pair(-1, -1)),
                entry(pair(-1, 1),  pair(-1, -1)),
                entry(pair(-1, 2),  pair(-1, -1)),
                entry(pair(0, -2),  pair(-1, -1)),
                entry(pair(0, -1),  pair(-1, -1)),
                entry(pair(1, -2),  pair(-1, -1)),
                entry(pair(1, -1),  pair(-1, -1)),
                entry(pair(2, -2),  pair(-1, -1)),
                entry(pair(2, -1),  pair(-1, -1)),

                // Non-negative but out-of-range cursor boundaries
                entry(pair(0, 2),   pair(0, 1)),
                entry(pair(1, 2),   pair(1, 1)),
                entry(pair(2, 0),   pair(1, 0)),
                entry(pair(2, 1),   pair(1, 1)),
                entry(pair(2, 2),   pair(1, 1))
            )
        );
    }

    @Test
    public void testPinyinDecoding() {
        final byte[] utf8StrBytes = new byte[]{
            (byte)0xE5, (byte)0xAD, (byte)0xA6,
            (byte)0xE8, (byte)0x80, (byte)0x8C,
            (byte)0xE4, (byte)0xB8, (byte)0x8D,
            (byte)0xE6, (byte)0x80, (byte)0x9D,
            (byte)0xE5, (byte)0x88, (byte)0x99,
            (byte)0xE7, (byte)0xBD, (byte)0x94,
            (byte)0xEF, (byte)0xBC, (byte)0x8C,
            (byte)0xE6, (byte)0x80, (byte)0x9D,
            (byte)0xE8, (byte)0x80, (byte)0x8C,
            (byte)0xE4, (byte)0xB8, (byte)0x8D,
            (byte)0xE5, (byte)0xAD, (byte)0xA6,
            (byte)0xE5, (byte)0x88, (byte)0x99,
            (byte)0xE6, (byte)0xAE, (byte)0x86
        };
        final String expectedText = "学而不思则罔，思而不学则殆";

        final int[] utf8ValidCursorIndices =
            new int[]{ 0,  3,  6,  9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39 };
        final int[] javaValidCursorIndices =
            new int[]{ 0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13 };

        final HashMap<Pair<Integer>, Pair<Integer>> testCases = new HashMap<>();

        // Section of valid data (not violating the protocol)

        testCases.put(pair(-1, -1), pair(-1, -1));
        for (int cursorBegin = 0; cursorBegin < utf8ValidCursorIndices.length; ++cursorBegin) {
            for (int cursorEnd = 0; cursorEnd < utf8ValidCursorIndices.length; ++cursorEnd) {
                testCases.put(
                    pair(utf8ValidCursorIndices[cursorBegin], utf8ValidCursorIndices[cursorEnd]),
                    pair(javaValidCursorIndices[cursorBegin], javaValidCursorIndices[cursorEnd])
                );
            }
        }

        testCases.putAll(Map.<Pair<Integer>, Pair<Integer>>ofEntries(
            // Section of invalid data that we expect the implementation to be robust and sensible for though

            // ("...", ..., -1) , ("...", -1, ...) -> ("...", -1, -1)
            entry(pair(-2, -2), pair(-1, -1)),
            entry(pair(-2, -1), pair(-1, -1)),
            entry(pair(-2, 0),  pair(-1, -1)),
            entry(pair(-2, 1),  pair(-1, -1)),
            entry(pair(-2, 2),  pair(-1, -1)),
            entry(pair(-2, 3),  pair(-1, -1)),
            entry(pair(-2, 4),  pair(-1, -1)),
            entry(pair(-2, 5),  pair(-1, -1)),
            entry(pair(-2, 6),  pair(-1, -1)),
            entry(pair(-2, 39), pair(-1, -1)),
            entry(pair(-1, -2), pair(-1, -1)),
            entry(pair(-1, -1), pair(-1, -1)),
            entry(pair(-1, 0),  pair(-1, -1)),
            entry(pair(-1, 1),  pair(-1, -1)),
            entry(pair(-1, 2),  pair(-1, -1)),
            entry(pair(-1, 3),  pair(-1, -1)),
            entry(pair(-1, 4),  pair(-1, -1)),
            entry(pair(-1, 5),  pair(-1, -1)),
            entry(pair(-1, 6),  pair(-1, -1)),
            entry(pair(-1, 39), pair(-1, -1)),
            entry(pair(0, -2),  pair(-1, -1)),
            entry(pair(0, -1),  pair(-1, -1)),
            entry(pair(1, -2),  pair(-1, -1)),
            entry(pair(1, -1),  pair(-1, -1)),
            entry(pair(2, -2),  pair(-1, -1)),
            entry(pair(2, -1),  pair(-1, -1)),
            entry(pair(3, -2),  pair(-1, -1)),
            entry(pair(3, -1),  pair(-1, -1)),
            entry(pair(4, -2),  pair(-1, -1)),
            entry(pair(4, -1),  pair(-1, -1)),
            entry(pair(5, -2),  pair(-1, -1)),
            entry(pair(5, -1),  pair(-1, -1)),
            entry(pair(6, -2),  pair(-1, -1)),
            entry(pair(6, -1),  pair(-1, -1)),
            entry(pair(39, -2), pair(-1, -1)),
            entry(pair(39, -1), pair(-1, -1)),

            // Non-negative but out-of-range cursor boundaries
            // 1. cursor_end is out-of-range
            entry(pair(0, 40),    pair(0, 13)),
            entry(pair(0, 41),    pair(0, 13)),
            entry(pair(0, 100),   pair(0, 13)),
            entry(pair(3, 40),    pair(1, 13)),
            entry(pair(3, 41),    pair(1, 13)),
            entry(pair(3, 100),   pair(1, 13)),
            entry(pair(6, 40),    pair(2, 13)),
            entry(pair(6, 41),    pair(2, 13)),
            entry(pair(6, 100),   pair(2, 13)),
            entry(pair(21, 40),   pair(7, 13)),
            entry(pair(21, 41),   pair(7, 13)),
            entry(pair(21, 100),  pair(7, 13)),
            entry(pair(39, 40),   pair(13, 13)),
            entry(pair(39, 41),   pair(13, 13)),
            entry(pair(39, 100),  pair(13, 13)),
            // 2. cursor_begin is out-of-range
            entry(pair(40, 0),    pair(13, 0)),
            entry(pair(41, 0),    pair(13, 0)),
            entry(pair(100, 0),   pair(13, 0)),
            entry(pair(40, 3),    pair(13, 1)),
            entry(pair(41, 3),    pair(13, 1)),
            entry(pair(100, 3),   pair(13, 1)),
            entry(pair(40, 6),    pair(13, 2)),
            entry(pair(41, 6),    pair(13, 2)),
            entry(pair(100, 6),   pair(13, 2)),
            entry(pair(40, 21),   pair(13, 7)),
            entry(pair(41, 21),   pair(13, 7)),
            entry(pair(100, 21),  pair(13, 7)),
            entry(pair(40, 39),   pair(13, 13)),
            entry(pair(41, 39),   pair(13, 13)),
            entry(pair(100, 39),  pair(13, 13)),
            // 3. both are out-of-range
            entry(pair(40, 40),   pair(13, 13)),
            entry(pair(40, 41),   pair(13, 13)),
            entry(pair(40, 100),  pair(13, 13)),
            entry(pair(41, 40),   pair(13, 13)),
            entry(pair(41, 41),   pair(13, 13)),
            entry(pair(41, 100),  pair(13, 13)),
            entry(pair(100, 40),  pair(13, 13)),
            entry(pair(100, 41),  pair(13, 13)),
            entry(pair(100, 100), pair(13, 13)),

            // In-range indices but pointing to a middle byte inside a code point
            // 1. cursor_end is invalid, expected to be set equal to cursor_begin
            entry(pair(0, 1),   pair(0, 0)),
            entry(pair(0, 2),   pair(0, 0)),
            entry(pair(0, 7),   pair(0, 0)),
            entry(pair(0, 8),   pair(0, 0)),
            entry(pair(0, 28),  pair(0, 0)),
            entry(pair(0, 29),  pair(0, 0)),
            entry(pair(0, 37),  pair(0, 0)),
            entry(pair(0, 38),  pair(0, 0)),
            entry(pair(12, 1),  pair(4, 4)),
            entry(pair(12, 2),  pair(4, 4)),
            entry(pair(12, 7),  pair(4, 4)),
            entry(pair(12, 8),  pair(4, 4)),
            entry(pair(12, 28), pair(4, 4)),
            entry(pair(12, 29), pair(4, 4)),
            entry(pair(12, 37), pair(4, 4)),
            entry(pair(12, 38), pair(4, 4)),
            entry(pair(39, 1),  pair(13, 13)),
            entry(pair(39, 2),  pair(13, 13)),
            entry(pair(39, 7),  pair(13, 13)),
            entry(pair(39, 8),  pair(13, 13)),
            entry(pair(39, 28), pair(13, 13)),
            entry(pair(39, 29), pair(13, 13)),
            entry(pair(39, 37), pair(13, 13)),
            entry(pair(39, 38), pair(13, 13)),
            // 1.1. Now, additionally cursor_begin is out-of-range and it's expected to get normalized first
            entry(pair(40, 1),  pair(13, 13)),
            entry(pair(40, 2),  pair(13, 13)),
            entry(pair(40, 7),  pair(13, 13)),
            entry(pair(40, 8),  pair(13, 13)),
            entry(pair(40, 28), pair(13, 13)),
            entry(pair(40, 29), pair(13, 13)),
            entry(pair(40, 37), pair(13, 13)),
            entry(pair(40, 38), pair(13, 13)),
            entry(pair(99, 1),  pair(13, 13)),
            entry(pair(99, 2),  pair(13, 13)),
            entry(pair(99, 7),  pair(13, 13)),
            entry(pair(99, 8),  pair(13, 13)),
            entry(pair(99, 28), pair(13, 13)),
            entry(pair(99, 29), pair(13, 13)),
            entry(pair(99, 37), pair(13, 13)),
            entry(pair(99, 38), pair(13, 13)),
            // 2. cursor_begin is invalid, both cursor_begin and cursor_end are expected to be set to the end of the string
            entry(pair(1, 0),   pair(13, 13)),
            entry(pair(2, 0),   pair(13, 13)),
            entry(pair(7, 0),   pair(13, 13)),
            entry(pair(8, 0),   pair(13, 13)),
            entry(pair(28, 0),  pair(13, 13)),
            entry(pair(29, 0),  pair(13, 13)),
            entry(pair(37, 0),  pair(13, 13)),
            entry(pair(38, 0),  pair(13, 13)),
            entry(pair(1, 12),  pair(13, 13)),
            entry(pair(2, 12),  pair(13, 13)),
            entry(pair(7, 12),  pair(13, 13)),
            entry(pair(8, 12),  pair(13, 13)),
            entry(pair(28, 12), pair(13, 13)),
            entry(pair(29, 12), pair(13, 13)),
            entry(pair(37, 12), pair(13, 13)),
            entry(pair(38, 12), pair(13, 13)),
            entry(pair(1, 39),  pair(13, 13)),
            entry(pair(2, 39),  pair(13, 13)),
            entry(pair(7, 39),  pair(13, 13)),
            entry(pair(8, 39),  pair(13, 13)),
            entry(pair(28, 39), pair(13, 13)),
            entry(pair(29, 39), pair(13, 13)),
            entry(pair(37, 39), pair(13, 13)),
            entry(pair(38, 39), pair(13, 13)),
            // 3. Both are invalid and expected to be set to the end of the string
            entry(pair(1, 17),  pair(13, 13)),
            entry(pair(2, 16),  pair(13, 13)),
            entry(pair(7, 14),  pair(13, 13)),
            entry(pair(8, 13),  pair(13, 13)),
            entry(pair(28, 11), pair(13, 13)),
            entry(pair(29, 10), pair(13, 13)),
            entry(pair(37, 5),  pair(13, 13)),
            entry(pair(38, 4),  pair(13, 13))
        ));

        testConversionResultValuesImpl(
            utf8StrBytes,
            expectedText,
            testCases
        );
    }

    @Test
    public void testNonBMPDecoding() {
        testConversionResultValuesImpl(
            new byte[]{
                (byte)0xF0, (byte)0x9D, (byte)0x95, (byte)0x8F,
                (byte)0xF0, (byte)0x9F, (byte)0x83, (byte)0x8F,
                (byte)0xF0, (byte)0x90, (byte)0x90, (byte)0xB7
            },
            "𝕏🃏𐐷",
            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                // Section of valid data (not violating the protocol)

                entry(pair(-1, -1), pair(-1, -1)),

                entry(pair(0, 0),   pair(0, 0)),
                entry(pair(0, 4),   pair(0, 2)),
                entry(pair(0, 8),   pair(0, 4)),
                entry(pair(0, 12),  pair(0, 6)),
                entry(pair(4, 0),   pair(2, 0)),
                entry(pair(4, 4),   pair(2, 2)),
                entry(pair(4, 8),   pair(2, 4)),
                entry(pair(4, 12),  pair(2, 6)),
                entry(pair(8, 0),   pair(4, 0)),
                entry(pair(8, 4),   pair(4, 2)),
                entry(pair(8, 8),   pair(4, 4)),
                entry(pair(8, 12),  pair(4, 6)),
                entry(pair(12, 0),  pair(6, 0)),
                entry(pair(12, 4),  pair(6, 2)),
                entry(pair(12, 8),  pair(6, 4)),
                entry(pair(12, 12), pair(6, 6)),

                // Section of invalid data that we expect the implementation to be robust and sensible for though

                // ("...", ..., -1) , ("...", -1, ...) -> ("...", -1, -1)
                entry(pair(-1, -2), pair(-1, -1)),
                entry(pair(-1, 0),  pair(-1, -1)),
                entry(pair(-1, 1),  pair(-1, -1)),
                entry(pair(-1, 2),  pair(-1, -1)),
                entry(pair(-1, 3),  pair(-1, -1)),
                entry(pair(-1, 4),  pair(-1, -1)),
                entry(pair(-1, 5),  pair(-1, -1)),
                entry(pair(-1, 6),  pair(-1, -1)),
                entry(pair(-1, 7),  pair(-1, -1)),
                entry(pair(-1, 8),  pair(-1, -1)),
                entry(pair(-1, 9),  pair(-1, -1)),
                entry(pair(-1, 10), pair(-1, -1)),
                entry(pair(-1, 11), pair(-1, -1)),
                entry(pair(-1, 12), pair(-1, -1)),
                entry(pair(-1, 13), pair(-1, -1)),
                entry(pair(-1, 99), pair(-1, -1)),
                entry(pair(-2, -1), pair(-1, -1)),
                entry(pair(0, -1),  pair(-1, -1)),
                entry(pair(1, -1),  pair(-1, -1)),
                entry(pair(2, -1),  pair(-1, -1)),
                entry(pair(3, -1),  pair(-1, -1)),
                entry(pair(4, -1),  pair(-1, -1)),
                entry(pair(5, -1),  pair(-1, -1)),
                entry(pair(6, -1),  pair(-1, -1)),
                entry(pair(7, -1),  pair(-1, -1)),
                entry(pair(8, -1),  pair(-1, -1)),
                entry(pair(9, -1),  pair(-1, -1)),
                entry(pair(10, -1), pair(-1, -1)),
                entry(pair(11, -1), pair(-1, -1)),
                entry(pair(12, -1), pair(-1, -1)),
                entry(pair(13, -1), pair(-1, -1)),
                entry(pair(99, -1), pair(-1, -1)),

                // Non-negative but out-of-range cursor boundaries
                // 1. cursor_end is out-of-range
                entry(pair(0, 13),  pair(0, 6)),
                entry(pair(0, 14),  pair(0, 6)),
                entry(pair(0, 99),  pair(0, 6)),
                entry(pair(4, 13),  pair(2, 6)),
                entry(pair(4, 14),  pair(2, 6)),
                entry(pair(4, 99),  pair(2, 6)),
                entry(pair(8, 13),  pair(4, 6)),
                entry(pair(8, 14),  pair(4, 6)),
                entry(pair(8, 99),  pair(4, 6)),
                entry(pair(12, 13), pair(6, 6)),
                entry(pair(12, 14), pair(6, 6)),
                entry(pair(12, 99), pair(6, 6)),
                // 2. cursor_begin is out-of-range
                entry(pair(13, 0),  pair(6, 0)),
                entry(pair(14, 0),  pair(6, 0)),
                entry(pair(99, 0),  pair(6, 0)),
                entry(pair(13, 4),  pair(6, 2)),
                entry(pair(14, 4),  pair(6, 2)),
                entry(pair(99, 4),  pair(6, 2)),
                entry(pair(13, 8),  pair(6, 4)),
                entry(pair(14, 8),  pair(6, 4)),
                entry(pair(99, 8),  pair(6, 4)),
                entry(pair(13, 12), pair(6, 6)),
                entry(pair(14, 12), pair(6, 6)),
                entry(pair(99, 12), pair(6, 6)),
                // 3. both are out-of-range
                entry(pair(13, 13), pair(6, 6)),
                entry(pair(14, 13), pair(6, 6)),
                entry(pair(99, 13), pair(6, 6)),
                entry(pair(13, 14), pair(6, 6)),
                entry(pair(14, 14), pair(6, 6)),
                entry(pair(99, 14), pair(6, 6)),
                entry(pair(13, 99), pair(6, 6)),
                entry(pair(14, 99), pair(6, 6)),
                entry(pair(99, 99), pair(6, 6)),

                // In-range indices but pointing to a middle byte inside a code point
                // 1. cursor_end is invalid, expected to be set equal to cursor_begin
                entry(pair(0, 1),   pair(0, 0)),
                entry(pair(0, 2),   pair(0, 0)),
                entry(pair(0, 3),   pair(0, 0)),
                entry(pair(0, 5),   pair(0, 0)),
                entry(pair(0, 6),   pair(0, 0)),
                entry(pair(0, 7),   pair(0, 0)),
                entry(pair(0, 9),   pair(0, 0)),
                entry(pair(0, 10),  pair(0, 0)),
                entry(pair(0, 11),  pair(0, 0)),
                entry(pair(8, 1),   pair(4, 4)),
                entry(pair(8, 2),   pair(4, 4)),
                entry(pair(8, 3),   pair(4, 4)),
                entry(pair(8, 5),   pair(4, 4)),
                entry(pair(8, 6),   pair(4, 4)),
                entry(pair(8, 7),   pair(4, 4)),
                entry(pair(8, 9),   pair(4, 4)),
                entry(pair(8, 10),  pair(4, 4)),
                entry(pair(8, 11),  pair(4, 4)),
                entry(pair(12, 1),  pair(6, 6)),
                entry(pair(12, 2),  pair(6, 6)),
                entry(pair(12, 3),  pair(6, 6)),
                entry(pair(12, 5),  pair(6, 6)),
                entry(pair(12, 6),  pair(6, 6)),
                entry(pair(12, 7),  pair(6, 6)),
                entry(pair(12, 9),  pair(6, 6)),
                entry(pair(12, 10), pair(6, 6)),
                entry(pair(12, 11), pair(6, 6)),
                // 1.1. Now, additionally cursor_begin is out-of-range and it's expected to get normalized first
                entry(pair(13, 1),  pair(6, 6)),
                entry(pair(13, 2),  pair(6, 6)),
                entry(pair(13, 3),  pair(6, 6)),
                entry(pair(13, 5),  pair(6, 6)),
                entry(pair(13, 6),  pair(6, 6)),
                entry(pair(13, 7),  pair(6, 6)),
                entry(pair(13, 9),  pair(6, 6)),
                entry(pair(13, 10), pair(6, 6)),
                entry(pair(13, 11), pair(6, 6)),
                entry(pair(99, 1),  pair(6, 6)),
                entry(pair(99, 2),  pair(6, 6)),
                entry(pair(99, 3),  pair(6, 6)),
                entry(pair(99, 5),  pair(6, 6)),
                entry(pair(99, 6),  pair(6, 6)),
                entry(pair(99, 7),  pair(6, 6)),
                entry(pair(99, 9),  pair(6, 6)),
                entry(pair(99, 10), pair(6, 6)),
                entry(pair(99, 11), pair(6, 6)),
                // 2. cursor_begin is invalid, both cursor_begin and cursor_end are expected to be set to the end of the string
                entry(pair(1, 12),  pair(6, 6)),
                entry(pair(2, 8),   pair(6, 6)),
                entry(pair(3, 4),   pair(6, 6)),
                entry(pair(5, 0),   pair(6, 6)),
                entry(pair(6, 4),   pair(6, 6)),
                entry(pair(7, 8),   pair(6, 6)),
                entry(pair(9, 12),  pair(6, 6)),
                entry(pair(10, 0),  pair(6, 6)),
                entry(pair(11, 8),  pair(6, 6)),
                // 3. Both are invalid and expected to be set to the end of the string
                entry(pair(7, 1),   pair(6, 6)),
                entry(pair(6, 2),   pair(6, 6)),
                entry(pair(1, 3),   pair(6, 6)),
                entry(pair(11, 5),  pair(6, 6)),
                entry(pair(3, 6),   pair(6, 6)),
                entry(pair(10, 7),  pair(6, 6)),
                entry(pair(2, 9),   pair(6, 6)),
                entry(pair(5, 10),  pair(6, 6)),
                entry(pair(9, 11),  pair(6, 6))
            )
        );
    }

    @Test
    public void testMixedBMPAndNonBMPDecoding() {
        testConversionResultValuesImpl(
            new byte[] {
                (byte)0x2D,
                (byte)0xCE, (byte)0x91,
                (byte)0x2B,
                (byte)0xF0, (byte)0x9F, (byte)0x82, (byte)0xA1,
                (byte)0x3D,
                (byte)0xE2, (byte)0x99, (byte)0xA0
            },
            "-Α+🂡=♠",
            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                // Section of valid data (not violating the protocol)

                entry(pair(-1, -1), pair(-1, -1)),

                entry(pair(0, 0),   pair(0, 0)),
                entry(pair(0, 1),   pair(0, 1)),
                entry(pair(0, 3),   pair(0, 2)),
                entry(pair(0, 4),   pair(0, 3)),
                entry(pair(0, 8),   pair(0, 5)),
                entry(pair(0, 9),   pair(0, 6)),
                entry(pair(0, 12),  pair(0, 7)),

                entry(pair(1, 0),   pair(1, 0)),
                entry(pair(1, 1),   pair(1, 1)),
                entry(pair(1, 3),   pair(1, 2)),
                entry(pair(1, 4),   pair(1, 3)),
                entry(pair(1, 8),   pair(1, 5)),
                entry(pair(1, 9),   pair(1, 6)),
                entry(pair(1, 12),  pair(1, 7)),

                entry(pair(3, 0),   pair(2, 0)),
                entry(pair(3, 1),   pair(2, 1)),
                entry(pair(3, 3),   pair(2, 2)),
                entry(pair(3, 4),   pair(2, 3)),
                entry(pair(3, 8),   pair(2, 5)),
                entry(pair(3, 9),   pair(2, 6)),
                entry(pair(3, 12),  pair(2, 7)),

                entry(pair(4, 0),   pair(3, 0)),
                entry(pair(4, 1),   pair(3, 1)),
                entry(pair(4, 3),   pair(3, 2)),
                entry(pair(4, 4),   pair(3, 3)),
                entry(pair(4, 8),   pair(3, 5)),
                entry(pair(4, 9),   pair(3, 6)),
                entry(pair(4, 12),  pair(3, 7)),

                entry(pair(8, 0),   pair(5, 0)),
                entry(pair(8, 1),   pair(5, 1)),
                entry(pair(8, 3),   pair(5, 2)),
                entry(pair(8, 4),   pair(5, 3)),
                entry(pair(8, 8),   pair(5, 5)),
                entry(pair(8, 9),   pair(5, 6)),
                entry(pair(8, 12),  pair(5, 7)),

                entry(pair(9, 0),   pair(6, 0)),
                entry(pair(9, 1),   pair(6, 1)),
                entry(pair(9, 3),   pair(6, 2)),
                entry(pair(9, 4),   pair(6, 3)),
                entry(pair(9, 8),   pair(6, 5)),
                entry(pair(9, 9),   pair(6, 6)),
                entry(pair(9, 12),  pair(6, 7)),

                entry(pair(12, 0),  pair(7, 0)),
                entry(pair(12, 1),  pair(7, 1)),
                entry(pair(12, 3),  pair(7, 2)),
                entry(pair(12, 4),  pair(7, 3)),
                entry(pair(12, 8),  pair(7, 5)),
                entry(pair(12, 9),  pair(7, 6)),
                entry(pair(12, 12), pair(7, 7)),

                // Section of invalid data that we expect the implementation to be robust and sensible for though

                // ("...", ..., -1) , ("...", -1, ...) -> ("...", -1, -1)
                entry(pair(-2, -1), pair(-1, -1)),
                entry(pair(0, -1),  pair(-1, -1)),
                entry(pair(1, -1),  pair(-1, -1)),
                entry(pair(2, -1),  pair(-1, -1)),
                entry(pair(3, -1),  pair(-1, -1)),
                entry(pair(4, -1),  pair(-1, -1)),
                entry(pair(5, -1),  pair(-1, -1)),
                entry(pair(6, -1),  pair(-1, -1)),
                entry(pair(7, -1),  pair(-1, -1)),
                entry(pair(8, -1),  pair(-1, -1)),
                entry(pair(9, -1),  pair(-1, -1)),
                entry(pair(10, -1), pair(-1, -1)),
                entry(pair(11, -1), pair(-1, -1)),
                entry(pair(12, -1), pair(-1, -1)),
                entry(pair(13, -1), pair(-1, -1)),
                entry(pair(99, -1), pair(-1, -1)),
                entry(pair(-1, -2), pair(-1, -1)),
                entry(pair(-1, 0),  pair(-1, -1)),
                entry(pair(-1, 1),  pair(-1, -1)),
                entry(pair(-1, 2),  pair(-1, -1)),
                entry(pair(-1, 3),  pair(-1, -1)),
                entry(pair(-1, 4),  pair(-1, -1)),
                entry(pair(-1, 5),  pair(-1, -1)),
                entry(pair(-1, 6),  pair(-1, -1)),
                entry(pair(-1, 7),  pair(-1, -1)),
                entry(pair(-1, 8),  pair(-1, -1)),
                entry(pair(-1, 9),  pair(-1, -1)),
                entry(pair(-1, 10), pair(-1, -1)),
                entry(pair(-1, 11), pair(-1, -1)),
                entry(pair(-1, 12), pair(-1, -1)),
                entry(pair(-1, 13), pair(-1, -1)),
                entry(pair(-1, 99), pair(-1, -1)),

                // Non-negative but out-of-range cursor boundaries
                // 1. cursor_end is out-of-range
                entry(pair(0, 13),  pair(0, 7)),
                entry(pair(0, 99),  pair(0, 7)),
                entry(pair(1, 13),  pair(1, 7)),
                entry(pair(1, 99),  pair(1, 7)),
                entry(pair(3, 13),  pair(2, 7)),
                entry(pair(3, 99),  pair(2, 7)),
                entry(pair(4, 13),  pair(3, 7)),
                entry(pair(4, 99),  pair(3, 7)),
                entry(pair(8, 13),  pair(5, 7)),
                entry(pair(8, 99),  pair(5, 7)),
                entry(pair(9, 13),  pair(6, 7)),
                entry(pair(9, 99),  pair(6, 7)),
                entry(pair(12, 13), pair(7, 7)),
                entry(pair(12, 99), pair(7, 7)),
                // 2. cursor_begin is out-of-range
                entry(pair(13, 0),  pair(7, 0)),
                entry(pair(99, 0),  pair(7, 0)),
                entry(pair(13, 1),  pair(7, 1)),
                entry(pair(99, 1),  pair(7, 1)),
                entry(pair(13, 3),  pair(7, 2)),
                entry(pair(99, 3),  pair(7, 2)),
                entry(pair(13, 4),  pair(7, 3)),
                entry(pair(99, 4),  pair(7, 3)),
                entry(pair(13, 8),  pair(7, 5)),
                entry(pair(99, 8),  pair(7, 5)),
                entry(pair(13, 9),  pair(7, 6)),
                entry(pair(99, 9),  pair(7, 6)),
                entry(pair(13, 12), pair(7, 7)),
                entry(pair(99, 12), pair(7, 7)),
                // 3. both are out-of-range
                entry(pair(13, 13), pair(7, 7)),
                entry(pair(99, 13), pair(7, 7)),
                entry(pair(13, 99), pair(7, 7)),
                entry(pair(99, 99), pair(7, 7)),

                // In-range indices but pointing to a middle byte inside a code point
                // 1. cursor_end is invalid, expected to be set equal to cursor_begin
                entry(pair(0, 2),   pair(0, 0)),
                entry(pair(0, 5),   pair(0, 0)),
                entry(pair(0, 6),   pair(0, 0)),
                entry(pair(0, 7),   pair(0, 0)),
                entry(pair(0, 10),  pair(0, 0)),
                entry(pair(0, 11),  pair(0, 0)),
                entry(pair(1, 2),   pair(1, 1)),
                entry(pair(1, 5),   pair(1, 1)),
                entry(pair(1, 6),   pair(1, 1)),
                entry(pair(1, 7),   pair(1, 1)),
                entry(pair(1, 10),  pair(1, 1)),
                entry(pair(1, 11),  pair(1, 1)),
                entry(pair(3, 2),   pair(2, 2)),
                entry(pair(3, 5),   pair(2, 2)),
                entry(pair(3, 6),   pair(2, 2)),
                entry(pair(3, 7),   pair(2, 2)),
                entry(pair(3, 10),  pair(2, 2)),
                entry(pair(3, 11),  pair(2, 2)),
                entry(pair(4, 2),   pair(3, 3)),
                entry(pair(4, 5),   pair(3, 3)),
                entry(pair(4, 6),   pair(3, 3)),
                entry(pair(4, 7),   pair(3, 3)),
                entry(pair(4, 10),  pair(3, 3)),
                entry(pair(4, 11),  pair(3, 3)),
                entry(pair(8, 2),   pair(5, 5)),
                entry(pair(8, 5),   pair(5, 5)),
                entry(pair(8, 6),   pair(5, 5)),
                entry(pair(8, 7),   pair(5, 5)),
                entry(pair(8, 10),  pair(5, 5)),
                entry(pair(8, 11),  pair(5, 5)),
                entry(pair(9, 2),   pair(6, 6)),
                entry(pair(9, 5),   pair(6, 6)),
                entry(pair(9, 6),   pair(6, 6)),
                entry(pair(9, 7),   pair(6, 6)),
                entry(pair(9, 10),  pair(6, 6)),
                entry(pair(9, 11),  pair(6, 6)),
                entry(pair(12, 2),  pair(7, 7)),
                entry(pair(12, 5),  pair(7, 7)),
                entry(pair(12, 6),  pair(7, 7)),
                entry(pair(12, 7),  pair(7, 7)),
                entry(pair(12, 10), pair(7, 7)),
                entry(pair(12, 11), pair(7, 7)),
                // 1.1. Now, additionally cursor_begin is out-of-range and it's expected to get normalized first
                entry(pair(13, 2),  pair(7, 7)),
                entry(pair(13, 5),  pair(7, 7)),
                entry(pair(13, 6),  pair(7, 7)),
                entry(pair(13, 7),  pair(7, 7)),
                entry(pair(13, 10), pair(7, 7)),
                entry(pair(13, 11), pair(7, 7)),
                entry(pair(99, 2),  pair(7, 7)),
                entry(pair(99, 5),  pair(7, 7)),
                entry(pair(99, 6),  pair(7, 7)),
                entry(pair(99, 7),  pair(7, 7)),
                entry(pair(99, 10), pair(7, 7)),
                entry(pair(99, 11), pair(7, 7)),
                // 2. cursor_begin is invalid, both cursor_begin and cursor_end are expected to be set to the end of the string
                entry(pair(2, 0),   pair(7, 7)),
                entry(pair(5, 0),   pair(7, 7)),
                entry(pair(6, 0),   pair(7, 7)),
                entry(pair(7, 0),   pair(7, 7)),
                entry(pair(10, 0),  pair(7, 7)),
                entry(pair(11, 0),  pair(7, 7)),
                entry(pair(2, 1),   pair(7, 7)),
                entry(pair(5, 1),   pair(7, 7)),
                entry(pair(6, 1),   pair(7, 7)),
                entry(pair(7, 1),   pair(7, 7)),
                entry(pair(10, 1),  pair(7, 7)),
                entry(pair(11, 1),  pair(7, 7)),
                entry(pair(2, 3),   pair(7, 7)),
                entry(pair(5, 3),   pair(7, 7)),
                entry(pair(6, 3),   pair(7, 7)),
                entry(pair(7, 3),   pair(7, 7)),
                entry(pair(10, 3),  pair(7, 7)),
                entry(pair(11, 3),  pair(7, 7)),
                entry(pair(2, 4),   pair(7, 7)),
                entry(pair(5, 4),   pair(7, 7)),
                entry(pair(6, 4),   pair(7, 7)),
                entry(pair(7, 4),   pair(7, 7)),
                entry(pair(10, 4),  pair(7, 7)),
                entry(pair(11, 4),  pair(7, 7)),
                entry(pair(2, 8),   pair(7, 7)),
                entry(pair(5, 8),   pair(7, 7)),
                entry(pair(6, 8),   pair(7, 7)),
                entry(pair(7, 8),   pair(7, 7)),
                entry(pair(10, 8),  pair(7, 7)),
                entry(pair(11, 8),  pair(7, 7)),
                entry(pair(2, 9),   pair(7, 7)),
                entry(pair(5, 9),   pair(7, 7)),
                entry(pair(6, 9),   pair(7, 7)),
                entry(pair(7, 9),   pair(7, 7)),
                entry(pair(10, 9),  pair(7, 7)),
                entry(pair(11, 9),  pair(7, 7)),
                entry(pair(2, 12),  pair(7, 7)),
                entry(pair(5, 12),  pair(7, 7)),
                entry(pair(6, 12),  pair(7, 7)),
                entry(pair(7, 12),  pair(7, 7)),
                entry(pair(10, 12), pair(7, 7)),
                entry(pair(11, 12), pair(7, 7)),
                // 3. Both are invalid and expected to be set to the end of the string
                entry(pair(2, 2),   pair(7, 7)),
                entry(pair(5, 2),   pair(7, 7)),
                entry(pair(6, 2),   pair(7, 7)),
                entry(pair(7, 2),   pair(7, 7)),
                entry(pair(10, 2),  pair(7, 7)),
                entry(pair(11, 2),  pair(7, 7)),
                entry(pair(2, 5),   pair(7, 7)),
                entry(pair(5, 5),   pair(7, 7)),
                entry(pair(6, 5),   pair(7, 7)),
                entry(pair(7, 5),   pair(7, 7)),
                entry(pair(10, 5),  pair(7, 7)),
                entry(pair(11, 5),  pair(7, 7)),
                entry(pair(2, 6),   pair(7, 7)),
                entry(pair(5, 6),   pair(7, 7)),
                entry(pair(6, 6),   pair(7, 7)),
                entry(pair(7, 6),   pair(7, 7)),
                entry(pair(10, 6),  pair(7, 7)),
                entry(pair(11, 6),  pair(7, 7)),
                entry(pair(2, 7),   pair(7, 7)),
                entry(pair(5, 7),   pair(7, 7)),
                entry(pair(6, 7),   pair(7, 7)),
                entry(pair(7, 7),   pair(7, 7)),
                entry(pair(10, 7),  pair(7, 7)),
                entry(pair(11, 7),  pair(7, 7)),
                entry(pair(2, 10),   pair(7, 7)),
                entry(pair(5, 10),   pair(7, 7)),
                entry(pair(6, 10),   pair(7, 7)),
                entry(pair(7, 10),   pair(7, 7)),
                entry(pair(10, 10),  pair(7, 7)),
                entry(pair(11, 10),  pair(7, 7)),
                entry(pair(2, 11),   pair(7, 7)),
                entry(pair(5, 11),   pair(7, 7)),
                entry(pair(6, 11),   pair(7, 7)),
                entry(pair(7, 11),   pair(7, 7)),
                entry(pair(10, 11),  pair(7, 7)),
                entry(pair(11, 11),  pair(7, 7))
            )
        );
    }


    @Test
    public void testTrimmingTrailingZeroes() {
        testConversionResultValuesImpl(
            new byte[]{ 0 },
            "",
            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                entry(pair(0, 1), pair(0, 0)),
                entry(pair(1, 0), pair(0, 0)),
                entry(pair(1, 1), pair(0, 0))
            )
        );

        testConversionResultValuesImpl(
            new byte[]{ 0, 0, 0 },
            "",
            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                entry(pair(0, 1), pair(0, 0)),
                entry(pair(0, 2), pair(0, 0)),
                entry(pair(0, 3), pair(0, 0)),
                entry(pair(1, 1), pair(0, 0)),
                entry(pair(1, 2), pair(0, 0)),
                entry(pair(1, 3), pair(0, 0)),
                entry(pair(2, 1), pair(0, 0)),
                entry(pair(2, 2), pair(0, 0)),
                entry(pair(2, 3), pair(0, 0)),
                entry(pair(3, 1), pair(0, 0)),
                entry(pair(3, 2), pair(0, 0)),
                entry(pair(3, 3), pair(0, 0))
            )
        );

        testConversionResultValuesImpl(
            new byte[]{ 0x61, 0, 0, 0, 0, 0 },
            "a",
            Map.<Pair<Integer>, Pair<Integer>>ofEntries(
                entry(pair(0, 2), pair(0, 1)),
                entry(pair(0, 3), pair(0, 1)),
                entry(pair(0, 4), pair(0, 1)),
                entry(pair(0, 5), pair(0, 1)),
                entry(pair(0, 6), pair(0, 1)),
                entry(pair(2, 0), pair(1, 0)),
                entry(pair(2, 2), pair(1, 1)),
                entry(pair(2, 3), pair(1, 1)),
                entry(pair(2, 4), pair(1, 1)),
                entry(pair(2, 5), pair(1, 1)),
                entry(pair(2, 6), pair(1, 1)),
                entry(pair(3, 0), pair(1, 0)),
                entry(pair(3, 2), pair(1, 1)),
                entry(pair(3, 3), pair(1, 1)),
                entry(pair(3, 4), pair(1, 1)),
                entry(pair(3, 5), pair(1, 1)),
                entry(pair(3, 6), pair(1, 1)),
                entry(pair(4, 0), pair(1, 0)),
                entry(pair(4, 2), pair(1, 1)),
                entry(pair(4, 3), pair(1, 1)),
                entry(pair(4, 4), pair(1, 1)),
                entry(pair(4, 5), pair(1, 1)),
                entry(pair(4, 6), pair(1, 1)),
                entry(pair(5, 0), pair(1, 0)),
                entry(pair(5, 2), pair(1, 1)),
                entry(pair(5, 3), pair(1, 1)),
                entry(pair(5, 4), pair(1, 1)),
                entry(pair(5, 5), pair(1, 1)),
                entry(pair(5, 6), pair(1, 1)),
                entry(pair(6, 0), pair(1, 0)),
                entry(pair(6, 2), pair(1, 1)),
                entry(pair(6, 3), pair(1, 1)),
                entry(pair(6, 4), pair(1, 1)),
                entry(pair(6, 5), pair(1, 1)),
                entry(pair(6, 6), pair(1, 1))
            )
        );
    }


    @Test
    public void testInvalidUtf8Encoding_invalidFirstByte() {
        testConversionExceptionImpl(
            new byte[]{
                (byte)0b111_00100,
                (byte)0b10_100011
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                (byte)0b1111_0001,
                (byte)0b10_001000,
                (byte)0b10_110100
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                (byte)0b11111_000,
                (byte)0b10_010010,
                (byte)0b10_001101,
                (byte)0b10_000101
            },
            CharacterCodingException.class
        );
    }

    @Test
    public void testInvalidUtf8Encoding_onlyMiddleByte() {
        testConversionExceptionImpl(
            new byte[]{
                (byte)0b10_111011
            },
            CharacterCodingException.class
        );
    }

    @Test
    public void testInvalidUtf8Encoding_noSecondByte() {
        testConversionExceptionImpl(
            new byte[]{
                // A first byte of a two-byte sequence
                (byte)0b110_10100
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                // A first byte of a three-byte sequence
                (byte)0b1110_0101
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                // A first byte of a four-byte sequence
                (byte)0b11110_011
            },
            CharacterCodingException.class
        );
    }

    @Test
    public void testInvalidUtf8Encoding_noThirdByte() {
        testConversionExceptionImpl(
            new byte[]{
                // Two first bytes of a three-byte sequence
                (byte)0b1110_0101,
                (byte)0b10_100111
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                // Two first bytes of a four-byte sequence
                (byte)0b11110_011,
                (byte)0b10_100111
            },
            CharacterCodingException.class
        );
    }

    @Test
    public void testInvalidUtf8Encoding_noFourthByte() {
        testConversionExceptionImpl(
            new byte[]{
                // Three first bytes of a four-byte sequence
                (byte)0b11110_011,
                (byte)0b10_100111,
                (byte)0b10_010101
            },
            CharacterCodingException.class
        );
    }

    @Test
    public void testInvalidUtf8Encoding_corruptedPinyin() {
        testConversionExceptionImpl(
            new byte[]{
                (byte)0xE5, (byte)0xAD,     (byte)0xA6,
                (byte)0xE8, (byte)0x80,     (byte)0x8C,
                (byte)0xE4, (byte)0xB8,     (byte)0x8D,
                (byte)0xE6, (byte)0x80,     (byte)0x9D,
                (byte)0xE5, (byte)0x88,     (byte)0x99,
                (byte)0xE7, (byte)0xBD,     (byte)0x94,
                (byte)0xEF, (byte)0xBC,     (byte)0x8C,
                (byte)0xE6, (byte)0x80,     (byte)0x9D,
                (byte)0xE8, (byte)0x80,     (byte)0x8C,
                (byte)0xE4, (byte)0xB8,     (byte)0x8D,
                (byte)0xE5, (byte)0xAD,     (byte)0xA6,
                (byte)0xE5, (byte)0x88,     (byte)0x99,
                // A middle byte of a three-byte sequence is missing
                //                 V
                (byte)0xE6, /*(byte)0xAE,*/ (byte)0x86
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                (byte)0xE5,        (byte)0xAD, (byte)0xA6,
                (byte)0xE8,        (byte)0x80, (byte)0x8C,
                (byte)0xE4,        (byte)0xB8, (byte)0x8D,
                (byte)0xE6,        (byte)0x80, (byte)0x9D,
                (byte)0xE5,        (byte)0x88, (byte)0x99,
                (byte)0xE7,        (byte)0xBD, (byte)0x94,
                (byte)0xEF,        (byte)0xBC, (byte)0x8C,
                (byte)0xE6,        (byte)0x80, (byte)0x9D,
                (byte)0xE8,        (byte)0x80, (byte)0x8C,
                (byte)0xE4,        (byte)0xB8, (byte)0x8D,
                (byte)0xE5,        (byte)0xAD, (byte)0xA6,
                (byte)0xE5,        (byte)0x88, (byte)0x99,
                (byte)0b11110_110, (byte)0xAE, (byte)0x86
                //   ^
                // A corrupted first byte of a three-byte sequence
            },
            CharacterCodingException.class
        );

        testConversionExceptionImpl(
            new byte[]{
                (byte)0xE5,        (byte)0xAD, (byte)0xA6,
                (byte)0xE8,        (byte)0x80, (byte)0x8C,
                (byte)0xE4,        (byte)0xB8, (byte)0x8D,
                (byte)0xE6,        (byte)0x80, (byte)0x9D,
                (byte)0xE5,        (byte)0x88, (byte)0x99,
                (byte)0xE7,        (byte)0xBD, (byte)0x94,
                (byte)0xEF,        (byte)0xBC, (byte)0x8C,
                (byte)0xE6,        (byte)0x80, (byte)0x9D,
                (byte)0xE8,        (byte)0x80, (byte)0x8C,
                (byte)0xE4,        (byte)0xB8, (byte)0x8D,
                (byte)0xE5,        (byte)0xAD, (byte)0xA6,
                (byte)0xE5,        (byte)0x88, (byte)0x99,
                (byte)0b110_00110, (byte)0xAE, (byte)0x86
                //   ^
                // A corrupted (in a different way) first byte of a three-byte sequence
            },
            CharacterCodingException.class
        );
    }


    private record Pair<T>(T first, T second) {}

    private static <T> Pair<T> pair(T first, T second) {
        return new Pair<>(first, second);
    }


    private void testConversionResultValuesImpl(
        final byte[] utf8StrBytes,
        final String expectedText,

        final Map<Pair<Integer>, Pair<Integer>> utf8CursorToExpectedJavaCursor
    ) {
        final SoftAssert sa = new SoftAssert();

        try {
            utf8CursorToExpectedJavaCursor.forEach((utf8Cursor, expectedJavaCursor) -> {
                try {
                    sa.assertEquals(
                        JavaPreeditString.fromWaylandPreeditString(utf8StrBytes, utf8Cursor.first(), utf8Cursor.second()),
                        new JavaPreeditString(expectedText, expectedJavaCursor.first(), expectedJavaCursor.second()),
                        "zwp_text_input_v3::preedit_string(%s, %d, %d) -> JavaPreeditString(\"%s\", %d, %d)".formatted(byteArrayToString(utf8StrBytes), utf8Cursor.first(), utf8Cursor.second(), expectedText, expectedJavaCursor.first(), expectedJavaCursor.second())
                    );
                } catch (CharacterCodingException e) {
                    sa.fail("Decoding exception occurred", e);
                }
            });
        } finally {
            sa.assertAll();
        }
    }

    private <T extends Throwable> void testConversionExceptionImpl(
        final byte[] invalidUtf8StrBytes,
        Class<T> throwableInstanceOfClass
    ) {
        try {
            assertThrows(throwableInstanceOfClass, () -> {
                JavaPreeditString.fromWaylandPreeditString(invalidUtf8StrBytes, -1, -1);
            });
        } catch (AssertionError err) {
            final var replacementErr = new AssertionError(
                "zwp_text_input_v3::preedit_string(%s, -1, -1): %s".formatted(byteArrayToString(invalidUtf8StrBytes), err.getMessage()),
                err.getCause()
            );

            Arrays.stream(err.getSuppressed()).forEach(replacementErr::addSuppressed);
            replacementErr.setStackTrace(err.getStackTrace());

            throw replacementErr;
        }
    }

    private static String byteArrayToString(byte[] a) {
        if (a == null)
            return "null";
        int iMax = a.length - 1;
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            b.append("0x").append(Integer.toHexString(Byte.toUnsignedInt(a[i])));
            if (i == iMax)
                return b.append(']').toString();
            b.append(", ");
        }
    }
}