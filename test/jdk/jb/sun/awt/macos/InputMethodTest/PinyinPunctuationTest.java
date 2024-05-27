/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

/**
 * @test
 * @summary Regression test for IDEA-221385: Cannot input with half-width punctuation.
 * @modules java.desktop/sun.lwawt.macosx
 * @run main PinyinPunctuationTest
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import java.util.Arrays;
import java.util.List;

import static java.awt.event.KeyEvent.*;

public class PinyinPunctuationTest extends TestFixture {
    record Punctuation(int keyCode, int mods, String halfWidth, String fullWidth) {}

    List<Punctuation> punctuationList = Arrays.asList(
            new Punctuation(VK_COMMA, 0, ",", "\uff0c"),
            new Punctuation(VK_PERIOD, 0, ".", "\u3002"),
            new Punctuation(VK_SLASH, SHIFT_DOWN_MASK, "?", "\uff1f"),
            new Punctuation(VK_SEMICOLON, 0, ";", "\uff1b"),
            new Punctuation(VK_SEMICOLON, SHIFT_DOWN_MASK, ":", "\uff1a"),
            new Punctuation(VK_OPEN_BRACKET, 0, "[", "\u3010"),
            new Punctuation(VK_CLOSE_BRACKET, 0, "]", "\u3011")
    );

    private void testQuotes(String name) {
        section(name + ": single quotes");
        press(VK_QUOTE);
        press(VK_SPACE);
        press(VK_QUOTE);
        expectText("\u2018 \u2019");

        section(name + ": double quotes");
        press(VK_QUOTE, SHIFT_DOWN_MASK);
        press(VK_SPACE);
        press(VK_QUOTE, SHIFT_DOWN_MASK);
        expectText("\u201c \u201d");
    }

    @Override
    public void test() throws Exception {
        layout("com.apple.inputmethod.SCIM.ITABC");

        chineseUseHalfWidthPunctuation(true);

        for (var punctuation : punctuationList) {
            var punctuationName = punctuation.halfWidth;
            section("half-width: " + punctuationName);
            press(punctuation.keyCode, punctuation.mods);
            expectText(punctuation.halfWidth);
        }

        testQuotes("half-width");

        chineseUseHalfWidthPunctuation(false);

        for (var punctuation : punctuationList) {
            var punctuationName = punctuation.halfWidth; // halfWidth is intended here
            section("full-width: " + punctuationName);
            press(punctuation.keyCode, punctuation.mods);
            expectText(punctuation.fullWidth);
        }

        testQuotes("full-width");
    }

    public static void main(String[] args) {
        new PinyinPunctuationTest().run();
    }
}
