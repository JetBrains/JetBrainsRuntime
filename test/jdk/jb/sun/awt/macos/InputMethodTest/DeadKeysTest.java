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
 * @run main DeadKeysTest
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class DeadKeysTest extends TestFixture {
    static private final int VK_SECTION = 0x01000000+0x00A7;

    @Override
    public void test() throws Exception {
        layout("com.apple.keylayout.ABC");

        section("ABC: Acute accent + vowel");
        press(VK_E, ALT_DOWN_MASK);
        press(VK_A);
        expectText("\u00e1");

        section("ABC: Acute accent + consonant");
        press(VK_E, ALT_DOWN_MASK);
        press(VK_S);
        expectText("\u00b4s");

        section("ABC: Acute accent + space");
        press(VK_E, ALT_DOWN_MASK);
        press(VK_SPACE);
        expectText("\u00b4");

        section("German - Standard: Opt+K, Section = Dead circumflex below");
        layout("com.apple.keylayout.German-DIN-2137");
        press(VK_K, ALT_DOWN_MASK);
        press(VK_SECTION);
        press(VK_D);
        expectText("\u1e13");

        section("UnicodeHexInput: U+0041 = A");
        layout("com.apple.keylayout.UnicodeHexInput");
        press(VK_0, ALT_DOWN_MASK);
        press(VK_0, ALT_DOWN_MASK);
        press(VK_4, ALT_DOWN_MASK);
        press(VK_1, ALT_DOWN_MASK);
        expectText("A");
    }

    public static void main(String[] args) {
        new DeadKeysTest().run();
    }
}
