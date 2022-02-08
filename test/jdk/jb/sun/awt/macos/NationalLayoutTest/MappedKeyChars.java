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
