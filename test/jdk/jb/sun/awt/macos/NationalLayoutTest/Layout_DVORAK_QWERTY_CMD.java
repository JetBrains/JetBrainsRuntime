/*
 * Copyright 2000-2025 JetBrains s.r.o.
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
 * @summary Regression test for JBR-5851 "Dvorak - QWERTY ⌘" layout does not work as expected
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx
 * @run main/othervm -Dcom.sun.awt.reportDeadKeysAsNormal=false NationalLayoutTest DVORAK_QWERTY_CMD
 */

public enum Layout_DVORAK_QWERTY_CMD implements LayoutKey {

    // Enum name must be the same as KeyEvent.VK_ constant name corresponding to the key on US keyboard layout
    // Note that '\u0000' may be used if no char is mapped to a key + modifier or if one wants to skip its testing

    VK_SECTION       ('§', '§', '±', '±', '0'),
    VK_MINUS         ('[', '“', '{', '”', '\u001B'),
    VK_EQUALS        (']', '‘', '}', '’', '\u001D'),

    VK_OPEN_BRACKET  ('/', '÷', '?', '¿', '\u001B'),
    VK_CLOSE_BRACKET ('=', '≠', '+', '±', '\u001D'),

    VK_SEMICOLON     ('s',  'ß', 'S', 'Í', '\u0013'),
    VK_QUOTE         ('-', '–', '_', '—', '\''),
    VK_BACK_SLASH    ('\\', '«', '|', '»', '\u001C'),

    VK_BACK_QUOTE    (KeyChar.ch('`'), KeyChar.dead('`'), KeyChar.ch('~'), KeyChar.ch('`'), KeyChar.ch('`')),
    VK_COMMA         ('w', '∑', 'W', '„', '\u0017'),
    VK_PERIOD        ('v', '√', 'V', '◊', '\u0016'),
    VK_SLASH         ('z', 'Ω', 'Z', '¸', '\u001A'),

    VK_W (',', '≤', '<', '¯', '\u0017'),
    VK_D (KeyChar.ch('e'), KeyChar.dead('´') ,KeyChar.ch('E'), KeyChar.ch('´'), KeyChar.ch('\u0005')),
    VK_F (KeyChar.ch('u'), KeyChar.dead('¨'), KeyChar.ch('U'), KeyChar.ch('¨'), KeyChar.ch('\u0015')),
    VK_G (KeyChar.ch('i'), KeyChar.dead('ˆ'), KeyChar.ch('I'), KeyChar.ch('ˆ'), KeyChar.ch('\u0009')),

    ;

    // Common code for any LayoutKey enum

    private final Key key;

    Layout_DVORAK_QWERTY_CMD(char no, char alt, char shift, char alt_shift, char control) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift, control));
    }

    Layout_DVORAK_QWERTY_CMD(KeyChar no, KeyChar alt, KeyChar shift, KeyChar alt_shift, KeyChar control) {
        key = new Key(name(), new MappedKeyChars(no, alt, shift, alt_shift, control));
    }

    public Key getKey() {
        return key;
    }

    @Override
    public boolean isCmdQWERTY() {
        return true;
    }
}
