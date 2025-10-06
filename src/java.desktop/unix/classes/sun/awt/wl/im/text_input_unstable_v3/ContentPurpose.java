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


/**
 * The content purpose allows to specify the primary purpose of a text input.
 * This allows an input method to show special purpose input panels with extra characters or to disallow some characters.
 */
enum ContentPurpose {
    /** default input, allowing all characters */
    NORMAL  (0),
    /** allow only alphabetic characters */
    ALPHA   (1),
    /** allow only digits */
    DIGITS  (2),
    /** input a number (including decimal separator and sign) */
    NUMBER  (3),
    /** input a phone number */
    PHONE   (4),
    /** input an URL */
    URL     (5),
    /** input an email address */
    EMAIL   (6),
    /** input a name of a person */
    NAME    (7),
    /** input a password (combine with sensitive_data hint) */
    PASSWORD(8),
    /** input is a numeric password (combine with sensitive_data hint) */
    PIN     (9),
    /** input a date */
    DATE    (10),
    /** input a time */
    TIME    (11),
    /** input a date and time */
    DATETIME(12),
    /** input for a terminal */
    TERMINAL(13);

    public final int intValue;
    ContentPurpose(int intValue) {
        this.intValue = intValue;
    }
}
