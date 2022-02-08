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

/*
 * Defines chars mapped to the key on the current keyboard layout
 */
public class MappedKeyChars {

    private final KeyChar no_modifier;
    private final KeyChar alt;
    private final KeyChar shift;
    private final KeyChar alt_shift;
    private final KeyChar control;

    MappedKeyChars(char no_modifier, char alt, char shift, char alt_shift, char control) {
        this.no_modifier = KeyChar.ch(no_modifier);
        this.alt = KeyChar.ch(alt);
        this.shift = KeyChar.ch(shift);
        this.alt_shift = KeyChar.ch(alt_shift);
        this.control = KeyChar.ch(control);
    }

    MappedKeyChars(KeyChar  no_modifier, KeyChar alt, KeyChar shift, KeyChar alt_shift, KeyChar control) {
        this.no_modifier = no_modifier;
        this.alt = alt;
        this.shift = shift;
        this.alt_shift = alt_shift;
        this.control = control;
    }

    /*
     * Return char mapped to key + modifier on the current layout.
     * Return "undefined" char '\uffff', if modifier is a shortcut, i.e. other than Alt, Shift or Alt+Shift or empty.
     * Return "null" char '\u0000', if there is no char mapped to the key + modifier or its testing is skipped.
     */
    KeyChar getKeyChar(Modifier modifier) {
        if(modifier.isEmpty()) {
            return no_modifier;
        }
        if(modifier.isAlt()) {
            return alt;
        }
        if(modifier.isShift()) {
            return shift;
        }
        if(modifier.isAltShift()) {
            return alt_shift;
        }
        if(modifier.isControl()) {
            return control;
        }
        return KeyChar.ch(Character.MAX_VALUE);
    }

    /*
     * Return char mapped to the low registry key on the current layout, i.e. with no modifier.
     */
    KeyChar getKeyChar() {
        return no_modifier;
    }
}
