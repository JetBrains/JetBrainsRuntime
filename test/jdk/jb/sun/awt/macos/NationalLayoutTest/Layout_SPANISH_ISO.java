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
 * @run main NationalLayoutTest SPANISH_ISO
 */

/*
 * Enumerates keys under test for com.apple.keylayout.Spanish-ISO (macOS 10.14.5)
 */
public enum Layout_SPANISH_ISO implements LayoutKey {

    // Enum name must be the same as KeyEvent.VK_ constant name corresponding to the key on US keyboard layout
    // Note that '\u0000' may be used if no char is mapped to a key + modifier or if one wants to skip its testing

    VK_MINUS         ('\'', '´', '?', '¸'),
    VK_EQUALS        ('¡',  '‚', '¿', '˛'),

    VK_OPEN_BRACKET  (KeyChar.dead('`'), KeyChar.ch('['), KeyChar.dead('^'), KeyChar.ch('ˆ')),
    VK_CLOSE_BRACKET ('+', ']', '*', '±'),

    VK_SEMICOLON     (KeyChar.ch('ñ'),   KeyChar.dead('~'), KeyChar.ch('Ñ'),   KeyChar.ch('˜')),
    VK_QUOTE         (KeyChar.dead('´'), KeyChar.ch('{'),   KeyChar.dead('¨'), KeyChar.ch('«')),
    VK_BACK_SLASH    ('ç', '}', 'Ç', '»'),

    VK_BACK_QUOTE    ('<', '≤', '>', '≥'),
    VK_COMMA         (',', '„', ';', '\u0000'),
    VK_PERIOD        ('.', '…', ':', '…'),
    VK_SLASH         ('-', '–', '_', '—'),

    ;

    // Common code for any LayoutKey enum

    private final Key key;

    Layout_SPANISH_ISO(char no, char alt, char shift, char alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    Layout_SPANISH_ISO(KeyChar no, KeyChar alt, KeyChar shift, KeyChar alt_shift) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift));
    }

    public Key getKey() {
        return key;
    }
}
