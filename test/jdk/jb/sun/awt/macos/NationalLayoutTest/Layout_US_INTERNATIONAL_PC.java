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
 * @run main NationalLayoutTest US_INTERNATIONAL_PC
 */

/*
 * Enumerates keys under test for com.apple.keylayout.USInternational-PC (macOS 10.14.5)
 */
public enum Layout_US_INTERNATIONAL_PC implements LayoutKey {

    // Enum name must be the same as KeyEvent.VK_ constant name corresponding to the key on US keyboard layout
    // Note that '\u0000' may be used if no char is mapped to a key + modifier or if one wants to skip its testing

    VK_MINUS         ('-', '–', '_', '—'),
    VK_EQUALS        ('=', '≠', '+', '±'),

    VK_OPEN_BRACKET  ('[', '“', '{', '”'),
    VK_CLOSE_BRACKET (']', '‘', '}', '’'),

    VK_SEMICOLON     (';',  '…', ':', 'Ú'),
    // ' is a special dead symbol, which may add either acute or cedilla to the next key
    VK_QUOTE         (KeyChar.dead('\''), KeyChar.ch('æ'), KeyChar.dead('\"'), KeyChar.ch('Æ')),
    VK_BACK_SLASH    ('\\', '«', '|', '»'),

    VK_BACK_QUOTE    (KeyChar.dead('`'), KeyChar.dead('`'), KeyChar.dead('~'), KeyChar.ch('`')),
    VK_COMMA         (',', '≤', '<', '¯'),
    VK_PERIOD        ('.', '≥', '>', '˘'),
    VK_SLASH         ('/', '÷', '?', '¿'),

    ;

    // Common code for any LayoutKey enum

    private final Key key;

    Layout_US_INTERNATIONAL_PC(char no, char alt, char shift, char alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    Layout_US_INTERNATIONAL_PC(KeyChar no, KeyChar alt, KeyChar shift, KeyChar alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    public Key getKey() {
        return key;
    }
}
