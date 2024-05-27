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
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 * @modules java.desktop/sun.lwawt.macosx
 * @run main JapaneseYenBackslashTest
 */

import static java.awt.event.KeyEvent.*;

public class JapaneseYenBackslashTest extends TestFixture {
    static private final int ROBOT_KEYCODE_YEN_SYMBOL_JIS = 0x200025D;
    static private final String YEN_SYMBOL = "\u00a5";
    static private final String RIGHT_POINTING_DOUBLE_ANGLE_QUOTATION_MARK = "\u00bb";

    private boolean useBackslash;

    @Override
    public void test() throws Exception {
        romajiLayout("com.apple.keylayout.ABC");
        doTest(false);
        doTest(true);
    }

    private void doTest(boolean useBackslash) {
        this.useBackslash = useBackslash;
        japaneseUseBackslash(useBackslash);

        // We need to switch from and to the Romaji layout after the JapaneseIM is restarted.
        // I'd say it's a macOS bug, but I don't think Kotoeri really expects to be killed like this.
        layout("com.apple.keylayout.ABC");
        layout("com.apple.inputmethod.Kotoeri.RomajiTyping.Roman");

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
        section("Backslash");
        press(VK_BACK_SLASH);
        expectText(useBackslash ? "\\" : YEN_SYMBOL);
    }

    private void optBackslash() {
        section("Opt+Backslash");
        press(VK_BACK_SLASH, ALT_DOWN_MASK);
        expectText(useBackslash ? YEN_SYMBOL : "\\");
    }

    private void shiftBackslash() {
        section("Shift+Backslash");
        press(VK_BACK_SLASH, SHIFT_DOWN_MASK);
        expectText("|");
    }

    private void optShiftBackslash() {
        section("Opt+Shift+Backslash");
        press(VK_BACK_SLASH, SHIFT_DOWN_MASK | ALT_DOWN_MASK);
        expectText(RIGHT_POINTING_DOUBLE_ANGLE_QUOTATION_MARK);
    }

    private void optY() {
        section("Opt+Y");
        press(VK_Y, ALT_DOWN_MASK);
        expectText(useBackslash ? "\\" : YEN_SYMBOL);
    }

    private void yen() {
        section("Yen");
        press(ROBOT_KEYCODE_YEN_SYMBOL_JIS);
        expectText(useBackslash ? "\\" : YEN_SYMBOL);
    }

    private void optYen() {
        section("Opt+Yen");
        press(ROBOT_KEYCODE_YEN_SYMBOL_JIS, ALT_DOWN_MASK);
        expectText(useBackslash ? YEN_SYMBOL : "\\");
    }

    private void shiftYen() {
        section("Shift+Yen");
        press(ROBOT_KEYCODE_YEN_SYMBOL_JIS, SHIFT_DOWN_MASK);
        expectText("|");
    }

    private void optShiftYen() {
        section("Opt+Shift+Yen");
        press(ROBOT_KEYCODE_YEN_SYMBOL_JIS, SHIFT_DOWN_MASK | ALT_DOWN_MASK);
        expectText("|");
    }

    public static void main(String[] args) {
        new JapaneseYenBackslashTest().run();
    }
}
