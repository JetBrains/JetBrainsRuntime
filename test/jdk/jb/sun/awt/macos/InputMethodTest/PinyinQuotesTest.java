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
 * @summary Regression test for IDEA-271898: Cannot enter Chinese full-corner single and double quotes (IDEA: macOS Intel version)
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 * @run shell Runner.sh PinyinQuotesTest
 */

import static java.awt.event.KeyEvent.*;

public class PinyinQuotesTest implements Runnable {
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.inputmethod.SCIM.ITABC");
        singleQuotes();
        doubleQuotes();
    }

    private void singleQuotes() {
        InputMethodTest.section("Single quotes");

        // type the following: ' '
        InputMethodTest.type(VK_QUOTE, 0);
        InputMethodTest.type(VK_SPACE, 0);
        InputMethodTest.type(VK_QUOTE, 0);

        InputMethodTest.expect("\u2018 \u2019");
    }

    private void doubleQuotes() {
        InputMethodTest.section("Double quotes");

        // type the following: " "
        InputMethodTest.type(VK_QUOTE, SHIFT_DOWN_MASK);
        InputMethodTest.type(VK_SPACE, 0);
        InputMethodTest.type(VK_QUOTE, SHIFT_DOWN_MASK);

        InputMethodTest.expect("\u201c \u201d");
    }
}
