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
 * @modules java.desktop/sun.lwawt.macosx
 * @run main/othervm -Dcom.sun.awt.reportDeadKeysAsNormal=false InputMethodTest KeyCodesTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import sun.lwawt.macosx.LWCToolkit;

import static java.awt.event.KeyEvent.*;

public class KeyCodesTest implements Runnable {
    static private final int ROBOT_KEYCODE_BACK_QUOTE_ISO = 0x2000132;
    static private final int ROBOT_KEYCODE_RIGHT_COMMAND = 0x2000036;
    static private final int ROBOT_KEYCODE_RIGHT_SHIFT = 0x200003C;
    static private final int ROBOT_KEYCODE_RIGHT_CONTROL = 0x200003E;
    static private final int ROBOT_KEYCODE_YEN_SYMBOL_JIS = 0x200025D;
    static private final int ROBOT_KEYCODE_CIRCUMFLEX_JIS = 0x2000218;
    static private final int ROBOT_KEYCODE_NUMPAD_COMMA_JIS = 0x200025F;
    static private final int ROBOT_KEYCODE_NUMPAD_ENTER = 0x200004C;
    static private final int ROBOT_KEYCODE_NUMPAD_EQUALS = 0x2000051;
    static private final int VK_SECTION = 0x01000000+0x00A7;
    @Override
    public void run() {
        // ordinary non-letter character with VK_ key codes
        verify("!", VK_EXCLAMATION_MARK, "com.apple.keylayout.French-PC", VK_SLASH);
        verify("\"", VK_QUOTEDBL, "com.apple.keylayout.French-PC", VK_3);
        verify("#", VK_NUMBER_SIGN, "com.apple.keylayout.British-PC", VK_BACK_SLASH);
        verify("$", VK_DOLLAR, "com.apple.keylayout.French-PC", VK_CLOSE_BRACKET);
        verify("&", VK_AMPERSAND, "com.apple.keylayout.French-PC", VK_1);
        verify("'", VK_QUOTE, "com.apple.keylayout.French-PC", VK_4);
        verify("(", VK_LEFT_PARENTHESIS, "com.apple.keylayout.French-PC", VK_5);
        verify(")", VK_RIGHT_PARENTHESIS, "com.apple.keylayout.French-PC", VK_MINUS);
        verify("*", VK_ASTERISK, "com.apple.keylayout.French-PC", VK_BACK_SLASH);
        verify("+", VK_PLUS, "com.apple.keylayout.German", VK_CLOSE_BRACKET);
        verify(",", VK_COMMA, "com.apple.keylayout.ABC", VK_COMMA);
        verify("-", VK_MINUS, "com.apple.keylayout.ABC", VK_MINUS);
        verify(".", VK_PERIOD, "com.apple.keylayout.ABC", VK_PERIOD);
        verify("/", VK_SLASH, "com.apple.keylayout.ABC", VK_SLASH);
        verify(":", VK_COLON, "com.apple.keylayout.French-PC", VK_PERIOD);
        verify(";", VK_SEMICOLON, "com.apple.keylayout.ABC", VK_SEMICOLON);
        verify("<", VK_LESS, "com.apple.keylayout.French-PC", VK_BACK_QUOTE);
        verify("=", VK_EQUALS, "com.apple.keylayout.ABC", VK_EQUALS);
        verify(">", VK_GREATER, "com.apple.keylayout.Turkish", VK_CLOSE_BRACKET);
        verify("@", VK_AT, "com.apple.keylayout.Norwegian", VK_BACK_SLASH);
        verify("[", VK_OPEN_BRACKET, "com.apple.keylayout.ABC", VK_OPEN_BRACKET);
        verify("\\", VK_BACK_SLASH, "com.apple.keylayout.ABC", VK_BACK_SLASH);
        verify("]", VK_CLOSE_BRACKET, "com.apple.keylayout.ABC", VK_CLOSE_BRACKET);
        verify("^", VK_CIRCUMFLEX, "com.apple.keylayout.ABC", ROBOT_KEYCODE_CIRCUMFLEX_JIS);
        verify("_", VK_UNDERSCORE, "com.apple.keylayout.French-PC", VK_8);
        verify("`", VK_BACK_QUOTE, "com.apple.keylayout.ABC", VK_BACK_QUOTE);
        verify("{", VK_BRACELEFT, "com.apple.keylayout.LatinAmerican", VK_QUOTE);
        verify("}", VK_BRACERIGHT, "com.apple.keylayout.LatinAmerican", VK_BACK_SLASH);
        verify("\u00a1", VK_INVERTED_EXCLAMATION_MARK, "com.apple.keylayout.Spanish-ISO", VK_EQUALS);
        // TODO: figure out which keyboard layout has VK_EURO_SIGN as a key on the primary layer
        verify(" ", VK_SPACE, "com.apple.keylayout.ABC", VK_SPACE);

        // control characters
        verify("\t", VK_TAB, "com.apple.keylayout.ABC", VK_TAB);
        verify("\n", VK_ENTER, "com.apple.keylayout.ABC", VK_ENTER);
        verify("", VK_BACK_SPACE, "com.apple.keylayout.ABC", VK_BACK_SPACE);
        verify("", VK_ESCAPE, "com.apple.keylayout.ABC", VK_ESCAPE);

        // keypad
        verify("/", VK_DIVIDE, "com.apple.keylayout.ABC", VK_DIVIDE, VK_SLASH, KEY_LOCATION_NUMPAD, 0);
        verify("*", VK_MULTIPLY, "com.apple.keylayout.ABC", VK_MULTIPLY, VK_ASTERISK, KEY_LOCATION_NUMPAD, 0);
        verify("+", VK_ADD, "com.apple.keylayout.ABC", VK_ADD, VK_PLUS, KEY_LOCATION_NUMPAD, 0);
        verify("-", VK_SUBTRACT, "com.apple.keylayout.ABC", VK_SUBTRACT, VK_MINUS, KEY_LOCATION_NUMPAD, 0);
        verify("", VK_CLEAR, "com.apple.keylayout.ABC", VK_CLEAR, VK_UNDEFINED, KEY_LOCATION_NUMPAD, 0);
        verify("\n", VK_ENTER, "com.apple.keylayout.ABC", ROBOT_KEYCODE_NUMPAD_ENTER, VK_ENTER, KEY_LOCATION_NUMPAD, 0);
        verify(",", VK_COMMA, "com.apple.keylayout.ABC", ROBOT_KEYCODE_NUMPAD_COMMA_JIS, VK_COMMA, KEY_LOCATION_NUMPAD, 0);
        verify("=", VK_EQUALS, "com.apple.keylayout.ABC", ROBOT_KEYCODE_NUMPAD_EQUALS, VK_EQUALS, KEY_LOCATION_NUMPAD, 0);
        verify(".", VK_DECIMAL, "com.apple.keylayout.ABC", VK_DECIMAL, VK_PERIOD, KEY_LOCATION_NUMPAD, 0);

        // keypad numbers
        for (int i = 0; i < 10; ++i) {
            verify(String.valueOf((char)('0' + i)), VK_NUMPAD0 + i, "com.apple.keylayout.ABC", VK_NUMPAD0 + i, VK_0 + i, KEY_LOCATION_NUMPAD, 0);
        }

        // function keys
        verify("", VK_F1, "com.apple.keylayout.ABC", VK_F1);
        verify("", VK_F19, "com.apple.keylayout.ABC", VK_F19);

        // Test ANSI/ISO/JIS keyboard weirdness
        verify("\u00a7", 0x01000000+0x00A7, "com.apple.keylayout.ABC", VK_SECTION);
        verify("\u00b2", 0x01000000+0x00B2, "com.apple.keylayout.French-PC", VK_SECTION);
        verify("#", VK_NUMBER_SIGN, "com.apple.keylayout.CanadianFrench-PC", VK_SECTION);
        verify("\u00ab", 0x01000000+0x00AB, "com.apple.keylayout.CanadianFrench-PC", ROBOT_KEYCODE_BACK_QUOTE_ISO);
        verify("#", VK_NUMBER_SIGN, "com.apple.keylayout.CanadianFrench-PC", VK_BACK_QUOTE);
        verify("\u00a5", 0x01000000+0x00A5, "com.apple.keylayout.ABC", ROBOT_KEYCODE_YEN_SYMBOL_JIS);

        // Test extended key codes that don"t match the unicode char
        verify("\u00e4", 0x01000000+0x00C4, "com.apple.keylayout.German", VK_QUOTE);
        verify("\u00e5", 0x01000000+0x00C5, "com.apple.keylayout.Norwegian", VK_OPEN_BRACKET);
        verify("\u00e6", 0x01000000+0x00C6, "com.apple.keylayout.Norwegian", VK_QUOTE);
        verify("\u00e7", 0x01000000+0x00C7, "com.apple.keylayout.French-PC", VK_9);
        verify("\u00f1", 0x01000000+0x00D1, "com.apple.keylayout.Spanish-ISO", VK_SEMICOLON);
        verify("\u00f6", 0x01000000+0x00D6, "com.apple.keylayout.German", VK_SEMICOLON);
        verify("\u00f8", 0x01000000+0x00D8, "com.apple.keylayout.Norwegian", VK_SEMICOLON);

        // test modifier keys
        verify("", VK_ALT, "com.apple.keylayout.ABC", VK_ALT, VK_UNDEFINED, KEY_LOCATION_LEFT, ALT_DOWN_MASK);
        verify("", VK_ALT, "com.apple.keylayout.ABC", VK_ALT_GRAPH, VK_UNDEFINED, KEY_LOCATION_RIGHT, ALT_DOWN_MASK);
        verify("", VK_META, "com.apple.keylayout.ABC", VK_META, VK_UNDEFINED, KEY_LOCATION_LEFT, META_DOWN_MASK);
        verify("", VK_META, "com.apple.keylayout.ABC", ROBOT_KEYCODE_RIGHT_COMMAND, VK_UNDEFINED, KEY_LOCATION_RIGHT, META_DOWN_MASK);
        verify("", VK_CONTROL, "com.apple.keylayout.ABC", VK_CONTROL, VK_UNDEFINED, KEY_LOCATION_LEFT, CTRL_DOWN_MASK);
        verify("", VK_CONTROL, "com.apple.keylayout.ABC", ROBOT_KEYCODE_RIGHT_CONTROL, VK_UNDEFINED, KEY_LOCATION_RIGHT, CTRL_DOWN_MASK);
        verify("", VK_SHIFT, "com.apple.keylayout.ABC", VK_SHIFT, VK_UNDEFINED, KEY_LOCATION_LEFT, SHIFT_DOWN_MASK);
        verify("", VK_SHIFT, "com.apple.keylayout.ABC", ROBOT_KEYCODE_RIGHT_SHIFT, VK_UNDEFINED, KEY_LOCATION_RIGHT, SHIFT_DOWN_MASK);

        // duplicate key codes: Vietnamese ANSI_6 / ANSI_9
        verify(" \u0309", 0x1000000+0x0309, "com.apple.keylayout.Vietnamese", VK_6);
        verify(" \u0323", 0x1000000+0x0323, "com.apple.keylayout.Vietnamese", VK_9);

        // duplicated key codes (dead): Apache ANSI_LeftBracket / ANSI_RightBracket
        verify("\u02db", VK_DEAD_OGONEK, "com.apple.keylayout.Apache", VK_OPEN_BRACKET, 0x1000000+0x02DB, KEY_LOCATION_STANDARD, 0);
        verify("\u02db\u0301", 0x1000000+0x0301, "com.apple.keylayout.Apache", VK_CLOSE_BRACKET);
    }

    private void verify(String typed, int vk, String layout, int key, int charKeyCode, int location, int modifiers) {
        if (!LWCToolkit.isKeyboardLayoutInstalled(layout)) {
            System.out.printf("WARNING: Skipping key code test, vk = %d, layout = %s: this layout is not installed", vk, layout);
            return;
        }
        char ch = (typed.length() == 1) ? typed.charAt(0) : 0;
        InputMethodTest.section("Key code test: " + vk + ", layout: " + layout + ", char: " + String.format("U+%04X", (int)ch));
        InputMethodTest.layout(layout);
        InputMethodTest.type(key, 0);
        InputMethodTest.expectText(typed);

        if (ch != 0) {
            InputMethodTest.expectTrue(getExtendedKeyCodeForChar(ch) == charKeyCode, "getExtendedKeyCodeForChar");
        }

        InputMethodTest.expectKeyPress(vk, location, modifiers, true);
    }

    private void verify(String typed, int vk, String layout, int key) {
        verify(typed, vk, layout, key, vk, KEY_LOCATION_STANDARD, 0);
    }
}
