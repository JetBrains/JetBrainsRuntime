/*
 * Copyright 2025 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.awt.wl.im.text_input_unstable_v3;


/** Content hint is a bitmask to allow to modify the behavior of the text input */
enum ContentHint {
    /** no special behavior */
    NONE               (0x0),
    /** suggest word completions */
    COMPLETION         (0x1),
    /** suggest word corrections */
    SPELLCHECK         (0x2),
    /** switch to uppercase letters at the start of a sentence */
    AUTO_CAPITALIZATION(0x4),
    /** prefer lowercase letters */
    LOWERCASE          (0x8),
    /** prefer uppercase letters */
    UPPERCASE          (0x10),
    /** prefer casing for titles and headings (can be language dependent) */
    TITLECASE          (0x20),
    /** characters should be hidden */
    HIDDEN_TEXT        (0x40),
    /** typed text should not be stored */
    SENSITIVE_DATA     (0x80),
    /** just Latin characters should be entered */
    LATIN              (0x100),
    /** the text input is multiline */
    MULTILINE          (0x200);

    public final int intMask;
    ContentHint(int intMask) {
        this.intMask = intMask;
    }
}
