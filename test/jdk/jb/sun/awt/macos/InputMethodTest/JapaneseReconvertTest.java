/*
 * Copyright 2024 JetBrains s.r.o.
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

import static java.awt.event.KeyEvent.*;

/**
 * @test
 * @summary Regression test for JBR-7119 Converting to Hanja/Kanji on macOS doesn't replace the converted Hangul/Kana symbols
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest JapaneseReconvertTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

public class JapaneseReconvertTest implements Runnable {
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese");
        InputMethodTest.type(VK_N, 0);
        InputMethodTest.type(VK_I, 0);
        InputMethodTest.type(VK_H, 0);
        InputMethodTest.type(VK_O, 0);
        InputMethodTest.type(VK_N, 0);
        InputMethodTest.type(VK_G, 0);
        InputMethodTest.type(VK_O, 0);
        InputMethodTest.type(VK_ENTER, 0);
        InputMethodTest.expectText("日本語");

        InputMethodTest.type(VK_R, CTRL_DOWN_MASK | SHIFT_DOWN_MASK);
        InputMethodTest.type(VK_ENTER, 0);
        InputMethodTest.type(VK_ENTER, 0);
        InputMethodTest.expectText("日本語");
    }
}
