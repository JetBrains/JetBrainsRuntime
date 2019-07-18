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
 * Enumerates macOS keyboard layouts covered by this test
 */
public enum Layout {

    ABC                 ("ABC", Layout_ABC.values()),
    US_INTERNATIONAL_PC ("USInternational-PC", Layout_US_INTERNATIONAL_PC.values()),
    SPANISH_ISO         ("Spanish-ISO", Layout_SPANISH_ISO.values()),
    FRENCH_PC           ("French-PC", Layout_FRENCH_PC.values()),
    GERMAN              ("German", Layout_GERMAN.values()),

    ;

    // Real macOS keyboard layout name without "com.apple.keylayout." prefix
    private String name;
    // Array of test keys for the layout
    private LayoutKey[] layoutKeys;

    Layout(String name, LayoutKey[] layoutKeys) {
        this.name = name;
        this.layoutKeys = layoutKeys;
    }

    // Return array of test keys for the layout
    LayoutKey[] getLayoutKeys() {
        return layoutKeys;
    }

    @Override
    public String toString() {
        return "com.apple.keylayout." + name;
    }
}
