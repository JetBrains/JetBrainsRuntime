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
 * @summary Regression test for JBR-5558 macOS keyboard rewrite 2
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx
 * @run main JapaneseUnderlyingLayoutTest
 */

import static java.awt.event.KeyEvent.*;

public class JapaneseUnderlyingLayoutTest extends TestFixture {
    @Override
    public void test() throws Exception {
        testQwerty();
        testQwertz();
    }

    private void testQwerty() {
        romajiLayout("com.apple.keylayout.ABC");
        qwerty("com.apple.keylayout.US");
        qwerty("com.apple.keylayout.ABC");
        qwerty("com.apple.keylayout.Russian");
        qwerty("com.apple.inputmethod.Kotoeri.RomajiTyping.Roman");
        qwerty("com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese");
    }

    private void testQwertz() {
        romajiLayout("com.apple.keylayout.ABC-QWERTZ");
        qwertz("com.apple.keylayout.German");
        qwertz("com.apple.keylayout.ABC-QWERTZ");
        qwertz("com.apple.keylayout.Polish");
        qwertz("com.apple.inputmethod.Kotoeri.RomajiTyping.Roman");
        qwertz("com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese");
    }

    private void qwerty(String layout) {
        testImpl(layout, VK_Y);
    }

    private void qwertz(String layout) {
        testImpl(layout, VK_Z);
    }

    private void testImpl(String layout, int vkY) {
        layout(layout);
        section("Cmd " + layout);
        press(VK_Y, META_DOWN_MASK);
        expectKeyPressed(vkY, META_DOWN_MASK);

        section("Ctrl " + layout);
        press(VK_Y, CTRL_DOWN_MASK);
        expectKeyPressed(vkY, CTRL_DOWN_MASK);
    }

    public static void main(String[] args) {
        new JapaneseUnderlyingLayoutTest().run();
    }
}
