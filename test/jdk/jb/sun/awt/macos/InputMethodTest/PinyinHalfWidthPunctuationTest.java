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

/**
 * @test
 * @summary Regression test for IDEA-221385: Cannot input with half-width punctuation.
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest PinyinHalfWidthPunctuationTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class PinyinHalfWidthPunctuationTest implements Runnable {
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.inputmethod.SCIM.ITABC");
        InputMethodTest.setUseHalfWidthPunctuation(true);

        InputMethodTest.section("comma");
        InputMethodTest.type(VK_COMMA, 0);
        InputMethodTest.expectText(",");

        InputMethodTest.section("period");
        InputMethodTest.type(VK_PERIOD, 0);
        InputMethodTest.expectText(".");

        InputMethodTest.section("question mark");
        InputMethodTest.type(VK_SLASH, SHIFT_DOWN_MASK);
        InputMethodTest.expectText("?");

        InputMethodTest.section("semicolon");
        InputMethodTest.type(VK_SEMICOLON, 0);
        InputMethodTest.expectText(";");

        InputMethodTest.section("colon");
        InputMethodTest.type(VK_SEMICOLON, SHIFT_DOWN_MASK);
        InputMethodTest.expectText(":");

        InputMethodTest.section("left square bracket");
        InputMethodTest.type(VK_OPEN_BRACKET, 0);
        InputMethodTest.expectText("[");

        InputMethodTest.section("right square bracket");
        InputMethodTest.type(VK_CLOSE_BRACKET, 0);
        InputMethodTest.expectText("]");
    }
}
