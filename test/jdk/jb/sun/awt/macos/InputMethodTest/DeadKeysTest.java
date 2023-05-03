/*
 * Copyright (c) 2000-2023 JetBrains s.r.o.
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
 * @summary Regression test for JBR-5006 Dead keys exhibit invalid behavior on macOS
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest DeadKeysTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class DeadKeysTest implements Runnable {
    static private final int VK_SECTION = 0x01000000+0x00A7;
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.keylayout.ABC");

        InputMethodTest.section("ABC: Acute accent + vowel");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_A, 0);
        InputMethodTest.expectText("\u00e1");

        InputMethodTest.section("ABC: Acute accent + consonant");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_S, 0);
        InputMethodTest.expectText("\u00b4s");

        InputMethodTest.section("ABC: Acute accent + space");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_SPACE, 0);
        InputMethodTest.expectText("\u00b4");

        InputMethodTest.section("German - Standard: Opt+K, Section = Dead circumflex below");
        InputMethodTest.layout("com.apple.keylayout.German-DIN-2137");
        InputMethodTest.type(VK_K, ALT_DOWN_MASK);
        InputMethodTest.type(VK_SECTION, 0);
        InputMethodTest.type(VK_D, 0);
        InputMethodTest.expectText("\u1e13");

        InputMethodTest.section("UnicodeHexInput: U+0041 = A");
        InputMethodTest.layout("com.apple.keylayout.UnicodeHexInput");
        InputMethodTest.type(VK_0, ALT_DOWN_MASK);
        InputMethodTest.type(VK_0, ALT_DOWN_MASK);
        InputMethodTest.type(VK_4, ALT_DOWN_MASK);
        InputMethodTest.type(VK_1, ALT_DOWN_MASK);
        InputMethodTest.expectText("A");
    }
}
