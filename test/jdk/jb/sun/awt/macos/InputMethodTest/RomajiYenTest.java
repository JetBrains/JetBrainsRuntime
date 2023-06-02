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
 * @summary Regression test for JBR-5309: Minor keyboard inconsistencies on macOS
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest RomajiYenTest
 * @run main InputMethodTest RomajiYenBackslashTest
 */

import static java.awt.event.KeyEvent.*;

public class RomajiYenTest implements Runnable {
    private final boolean isBackslash;
    static private final int ROBOT_KEYCODE_YEN_SYMBOL_JIS = 0x200025D;
    static private final String YEN_SYMBOL = "\u00a5";
    static private final String RIGHT_POINTING_DOUBLE_ANGLE_QUOTATION_MARK = "\u00bb";

    public RomajiYenTest(boolean isBackslash) {
        this.isBackslash = isBackslash;
    }

    @Override
    public void run() {
        InputMethodTest.setUseBackslashInsteadOfYen(isBackslash);
        InputMethodTest.setRomajiLayout("com.apple.keylayout.ABC");
        InputMethodTest.layout("com.apple.inputmethod.Kotoeri.RomajiTyping.Roman");
        backslash();
        optBackslash();
        shiftBackslash();
        optShiftBackslash();
        optY();
        yen();
        optYen();
        shiftYen();
        optShiftYen();
    }

    private void backslash() {
        InputMethodTest.section("Backslash");
        InputMethodTest.type(VK_BACK_SLASH, 0);
        InputMethodTest.expectText(isBackslash ? "\\" : YEN_SYMBOL);
    }

    private void optBackslash() {
        InputMethodTest.section("Opt+Backslash");
        InputMethodTest.type(VK_BACK_SLASH, ALT_DOWN_MASK);
        InputMethodTest.expectText(isBackslash ? YEN_SYMBOL : "\\");
    }

    private void shiftBackslash() {
        InputMethodTest.section("Shift+Backslash");
        InputMethodTest.type(VK_BACK_SLASH, SHIFT_DOWN_MASK);
        InputMethodTest.expectText("|");
    }

    private void optShiftBackslash() {
        InputMethodTest.section("Opt+Shift+Backslash");
        InputMethodTest.type(VK_BACK_SLASH, SHIFT_DOWN_MASK | ALT_DOWN_MASK);
        InputMethodTest.expectText(RIGHT_POINTING_DOUBLE_ANGLE_QUOTATION_MARK);
    }

    private void optY() {
        InputMethodTest.section("Opt+Y");
        InputMethodTest.type(VK_Y, ALT_DOWN_MASK);
        InputMethodTest.expectText(isBackslash ? "\\" : YEN_SYMBOL);
    }

    private void yen() {
        InputMethodTest.section("Yen");
        InputMethodTest.type(ROBOT_KEYCODE_YEN_SYMBOL_JIS, 0);
        InputMethodTest.expectText(isBackslash ? "\\" : YEN_SYMBOL);
    }

    private void optYen() {
        InputMethodTest.section("Opt+Yen");
        InputMethodTest.type(ROBOT_KEYCODE_YEN_SYMBOL_JIS, ALT_DOWN_MASK);
        InputMethodTest.expectText(isBackslash ? YEN_SYMBOL : "\\");
    }

    private void shiftYen() {
        InputMethodTest.section("Shift+Yen");
        InputMethodTest.type(ROBOT_KEYCODE_YEN_SYMBOL_JIS, SHIFT_DOWN_MASK);
        InputMethodTest.expectText("|");
    }

    private void optShiftYen() {
        InputMethodTest.section("Opt+Shift+Yen");
        InputMethodTest.type(ROBOT_KEYCODE_YEN_SYMBOL_JIS, SHIFT_DOWN_MASK | ALT_DOWN_MASK);
        InputMethodTest.expectText("|");
    }
}
