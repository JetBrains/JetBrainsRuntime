/*
 * Copyright (c) 2000-2023 JetBrains s.r.o.
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
 * @summary Regression test for JBR-5173 macOS keyboard support rewrite
 * @run shell Runner.sh KeyCodesTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class KeyCodesTest implements Runnable {
    @Override
    public void run() {
        verify('!', VK_EXCLAMATION_MARK, "com.apple.keylayout.French-PC", VK_SLASH);
        verify('"', VK_QUOTEDBL, "com.apple.keylayout.French-PC", VK_3);
        verify('#', VK_NUMBER_SIGN, "com.apple.keylayout.British-PC", VK_BACK_SLASH);
        verify('$', VK_DOLLAR, "com.apple.keylayout.French-PC", VK_CLOSE_BRACKET);
        verify('&', VK_AMPERSAND, "com.apple.keylayout.French-PC", VK_1);
        verify('\'', VK_QUOTE, "com.apple.keylayout.French-PC", VK_4);
        verify('(', VK_LEFT_PARENTHESIS, "com.apple.keylayout.French-PC", VK_5);
        verify(')', VK_RIGHT_PARENTHESIS, "com.apple.keylayout.French-PC", VK_MINUS);
        verify('*', VK_ASTERISK, "com.apple.keylayout.French-PC", VK_BACK_SLASH);
        verify('+', VK_PLUS, "com.apple.keylayout.German", VK_CLOSE_BRACKET);
        verify(',', VK_COMMA, "com.apple.keylayout.ABC", VK_COMMA);
        verify('-', VK_MINUS, "com.apple.keylayout.ABC", VK_MINUS);
        verify('.', VK_PERIOD, "com.apple.keylayout.ABC", VK_PERIOD);
        verify('/', VK_SLASH, "com.apple.keylayout.ABC", VK_SLASH);
        verify(':', VK_COLON, "com.apple.keylayout.French-PC", VK_PERIOD);
        verify(';', VK_SEMICOLON, "com.apple.keylayout.ABC", VK_SEMICOLON);
        verify('<', VK_LESS, "com.apple.keylayout.French-PC", VK_BACK_QUOTE);
        verify('=', VK_EQUALS, "com.apple.keylayout.ABC", VK_EQUALS);
        // TODO: figure out which keyboard layout has VK_GREATER as a key on the primary layer
        verify('@', VK_AT, "com.apple.keylayout.Norwegian", VK_BACK_SLASH);
        verify('[', VK_OPEN_BRACKET, "com.apple.keylayout.ABC", VK_OPEN_BRACKET);
        verify('\\', VK_BACK_SLASH, "com.apple.keylayout.ABC", VK_BACK_SLASH);
        verify(']', VK_CLOSE_BRACKET, "com.apple.keylayout.ABC", VK_CLOSE_BRACKET);
        // TODO: figure out which keyboard layout has VK_CIRCUMFLEX as a key on the primary layer
        verify('_', VK_UNDERSCORE, "com.apple.keylayout.French-PC", VK_8);
        verify('`', VK_BACK_QUOTE, "com.apple.keylayout.ABC", VK_BACK_QUOTE);
        verify('{', VK_BRACELEFT, "com.apple.keylayout.LatinAmerican", VK_QUOTE);
        verify('}', VK_BRACERIGHT, "com.apple.keylayout.LatinAmerican", VK_BACK_SLASH);
        verify('\u00a1', VK_INVERTED_EXCLAMATION_MARK, "com.apple.keylayout.Spanish-ISO", VK_EQUALS);
        // TODO: figure out which keyboard layout has VK_EURO_SIGN as a key on the primary layer

        verify('\u00e4', 0x01000000+0x00C4, "com.apple.keylayout.German", VK_QUOTE);
        verify('\u00e5', 0x01000000+0x00C5, "com.apple.keylayout.Norwegian", VK_OPEN_BRACKET);
        verify('\u00e6', 0x01000000+0x00C6, "com.apple.keylayout.Norwegian", VK_QUOTE);
        verify('\u00e7', 0x01000000+0x00C7, "com.apple.keylayout.French-PC", VK_9);
        verify('\u00f1', 0x01000000+0x00D1, "com.apple.keylayout.Spanish-ISO", VK_SEMICOLON);
        verify('\u00f6', 0x01000000+0x00D6, "com.apple.keylayout.German", VK_SEMICOLON);
        verify('\u00f8', 0x01000000+0x00D8, "com.apple.keylayout.Norwegian", VK_SEMICOLON);
    }

    private void verify(char ch, int vk, String layout, int key) {
        InputMethodTest.section("Key code test: " + vk + ", char: " + ch);
        InputMethodTest.layout(layout);
        InputMethodTest.type(key, 0);
        InputMethodTest.expect(String.valueOf(ch));
        InputMethodTest.expectKeyCode(vk);
        InputMethodTest.expectTrue(getExtendedKeyCodeForChar(ch) == vk, "getExtendedKeyCodeForChar(ch) == vk");
    }
}
