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
 * @summary Regression test for JBR-5254: CapsLock and Chinese IMs don't work properly
 * @modules java.desktop/sun.lwawt.macosx
 * @run main ChineseCapsLockTest
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.awt.event.KeyEvent.*;

public class ChineseCapsLockTest extends TestFixture {
    private static final List<String> expectLowercase = new ArrayList<>(Arrays.asList(
            "com.apple.inputmethod.SCIM.ITABC",
            "com.apple.inputmethod.SCIM.Shuangpin",
            "com.apple.inputmethod.SCIM.WBH",
            "com.apple.inputmethod.TYIM.Cangjie",
            "com.apple.inputmethod.TYIM.Sucheng",
            "com.apple.inputmethod.TYIM.Stroke",
            "com.apple.inputmethod.TCIM.Zhuyin",
            "com.apple.inputmethod.TCIM.Cangjie",
            "com.apple.inputmethod.TCIM.ZhuyinEten",
            "com.apple.inputmethod.TCIM.Jianyi",
            "com.apple.inputmethod.TCIM.Pinyin",
            "com.apple.inputmethod.TCIM.Shuangpin",
            "com.apple.inputmethod.TCIM.WBH"
    ));

    // Wubi (Simplified) produces uppercase characters even in native apps.
    private static final List<String> expectUppercase = new ArrayList<>(Arrays.asList(
            "com.apple.inputmethod.SCIM.WBX"
    ));

    @Override
    public void test() throws Exception {
        for (String layout : expectLowercase) {
            testLatinTyping(layout, false);
        }

        for (String layout : expectUppercase) {
            testLatinTyping(layout, true);
        }
    }

    private void testLatinTyping(String layout, boolean expectUppercase) {
        section(layout);
        layout(layout);
        setCapsLockState(true);
        press(VK_A);
        press(VK_B);
        press(VK_C);
        expectText(expectUppercase ? "ABC" : "abc");
        press(VK_ESCAPE);
    }

    public static void main(String[] args) {
        new ChineseCapsLockTest().run();
    }
}
