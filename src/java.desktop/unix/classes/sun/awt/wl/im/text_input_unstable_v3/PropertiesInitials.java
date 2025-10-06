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

import java.awt.Rectangle;


interface PropertiesInitials {
    /** {@code zwp_text_input_v3::set_text_change_cause} */
    ChangeCause       TEXT_CHANGE_CAUSE = ChangeCause.INPUT_METHOD;
    /** {@code zwp_text_input_v3::set_content_type} (hint) */
    int               CONTENT_HINT      = ContentHint.NONE.intMask;
    /** {@code zwp_text_input_v3::set_content_type} (purpose) */
    ContentPurpose    CONTENT_PURPOSE   = ContentPurpose.NORMAL;
    /**
     * {@code zwp_text_input_v3::set_cursor_rectangle}.
     * <p>
     * The initial values describing a cursor rectangle are empty.
     * That means the text input does not support describing the cursor area.
     * If the empty values get applied, subsequent attempts to change them may have no effect.
     */
    Rectangle         CURSOR_RECTANGLE  = null;
    /** {@code zwp_text_input_v3::preedit_string} */
    JavaPreeditString PREEDIT_STRING    = new JavaPreeditString("", 0, 0);
    /** {@code zwp_text_input_v3::commit_string} */
    JavaCommitString  COMMIT_STRING     = new JavaCommitString("");
}
