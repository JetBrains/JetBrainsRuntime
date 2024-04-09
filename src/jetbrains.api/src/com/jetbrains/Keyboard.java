/*
 * Copyright 2000-2024 JetBrains s.r.o.
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

package com.jetbrains;

import java.awt.event.KeyEvent;

public interface Keyboard {
    /** (Integer) Java key code as if the keyboard was a US QWERTY one */
    String US_KEYCODE = "US_KEYCODE";

    /** (Integer) VK_DEAD_* keycode for the base key, or VK_UNDEFINED if the base key is not dead */
    String DEAD_KEYCODE = "DEAD_KEYCODE";

    /** (Integer) VK_DEAD_* keycode for the key with modifiers, or VK_UNDEFINED if the key with modifiers is not dead */
    String DEAD_KEYSTROKE = "DEAD_KEYSTROKE";

    /** (String) The characters that this specific event has generated as a sequence of KEY_TYPED events */
    String CHARACTERS = "CHARACTERS";

    /** (Integer) Raw platform-dependent keycode for the specific key on the keyboard */
    String RAW_KEYCODE = "RAW_KEYCODE";

    /** (String) Keyboard layout ID, associated with the event in an platform-dependent format */
    String KEYBOARD_LAYOUT = "KEYBOARD_LAYOUT";

    Object getKeyEventProperty(KeyEvent event, String name);
}