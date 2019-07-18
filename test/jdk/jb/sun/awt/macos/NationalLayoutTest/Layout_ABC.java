/*
 * Copyright 2000-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

    // Robot cannot press section sign key (16777383),
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
