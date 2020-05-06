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
 * @summary Regression test for IDEA-165950: National keyboard layouts support
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx
 * @run main NationalLayoutTest ABC
 */

/*
 * Enumerates keys under test for com.apple.keylayout.ABC (macOS 10.14.5)
 */
public enum Layout_ABC implements LayoutKey {

    // Enum name must be the same as KeyEvent.VK_ constant name corresponding to the key on US keyboard layout
    // Note that '\u0000' may be used if no char is mapped to a key + modifier or if one wants to skip its testing

    // TODO Robot cannot press section sign key (16777383),
    // located on the left side of the key 1 on the Apple International English keyboard
    // SECTION       ('§', '§', '±', '±'),

    VK_MINUS         ('-', '–', '_', '—'),
    VK_EQUALS        ('=', '≠', '+', '±'),

    VK_OPEN_BRACKET  ('[', '“', '{', '”'),
    VK_CLOSE_BRACKET (']', '‘', '}', '’'),

    VK_SEMICOLON     (';',  '…', ':', 'Ú'),
    VK_QUOTE         ('\'', 'æ', '"', 'Æ'),
    VK_BACK_SLASH    ('\\', '«', '|', '»'),

    VK_BACK_QUOTE    (KeyChar.ch('`'), KeyChar.dead('`'), KeyChar.ch('~'), KeyChar.ch('`')),
    VK_COMMA         (',', '≤', '<', '¯'),
    VK_PERIOD        ('.', '≥', '>', '˘'),
    VK_SLASH         ('/', '÷', '?', '¿'),

    //VK_1 ('1', '¡', '!', '⁄'),
    //VK_2 ('2', '™', '@', '€'),
    //VK_3 ('3', '£', '#', '‹'),
    //VK_4 ('4', '¢', '$', '›'),
    //VK_5 ('5', '∞', '%', 'ﬁ'),
    //VK_6 ('6', '§', '^', 'ﬂ'),
    //VK_7 ('7', '¶', '&', '‡'),
    //VK_8 ('8', '•', '*', '°'),
    //VK_9 ('9', 'ª', '(', '·'),
    //VK_0 ('0', 'º', ')', '‚'),

    // macOS system shortcuts: Shift+Cmd+Q - Log Out, Ctrl+Cmd+Q - Lock Screen
    //VK_Q ('q', 'œ', 'Q', 'Œ'),
    //VK_W ('w', '∑', 'W', '„'),
    //VK_E (KeyChar.ch('e'), KeyChar.dead('´') ,KeyChar.ch('E'), KeyChar.ch('´')),
    //VK_R ('r', '®', 'R', '‰'),
    //VK_T ('t', '†', 'T', 'ˇ'),
    //VK_Y ('y', '¥', 'Y', 'Á'),
    //VK_U (KeyChar.ch('u'), KeyChar.dead('¨'), KeyChar.ch('U'), KeyChar.ch('¨')),
    //VK_I (KeyChar.ch('i'), KeyChar.dead('ˆ'), KeyChar.ch('I'), KeyChar.ch('ˆ')),
    //VK_O ('o', 'ø', 'O', 'Ø'),
    //VK_P ('p', 'π', 'P', '∏'),

    //VK_A ('a', 'å', 'A', 'Å'),
    //VK_S ('s', 'ß', 'S', 'Í'),
    //VK_D ('d', '∂', 'D', 'Î'),
    //VK_F ('f', 'ƒ', 'F', 'Ï'),
    //VK_G ('g', '©', 'G', '˝'),
    // macOS system shortcuts: Cmd+H - Hide the windows of the front app
    //VK_H ('h', '˙', 'H', 'Ó'),
    //VK_J ('j', '∆', 'J', 'Ô'),
    //VK_K ('k', '˚', 'K', ''),
    //VK_L ('l', '¬', 'L', 'Ò'),

    //VK_Z ('z', 'Ω', 'Z', '¸'),
    //VK_X ('x', '≈', 'X', '˛'),
    //VK_C ('c', 'ç', 'C', 'Ç'),
    // macOS system shortcuts: Cmd+V -  Paste the contents of the Clipboard into the current document or app
    //VK_V ('v', '√', 'V', '◊'),
    //VK_B ('b', '∫', 'B', 'ı'),
    //VK_N (KeyChar.ch('n'), KeyChar.dead('˜'), KeyChar.ch('N'), KeyChar.ch('˜')),
    //VK_M ('m', 'µ', 'M', 'Â'),

    ;

    // Common code for any LayoutKey enum

    private final Key key;

    Layout_ABC(char no, char alt, char shift, char alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    Layout_ABC(KeyChar no, KeyChar alt, KeyChar shift, KeyChar alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    public Key getKey() {
        return key;
    }
}
