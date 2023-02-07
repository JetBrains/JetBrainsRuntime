/*
 * Copyright (c) 2000-2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @test
 * @summary Regression test for JBR-5254: CapsLock and Chinese IMs don't work properly
 * @run shell Runner.sh PinyinCapsLockTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.awt.event.KeyEvent.*;

public class PinyinCapsLockTest implements Runnable {
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
    public void run() {
        for (String layout : expectLowercase) {
            testLatinTyping(layout, false);
        }

        for (String layout : expectUppercase) {
            testLatinTyping(layout, true);
        }
    }

    private void testLatinTyping(String layout, boolean expectUppercase) {
        InputMethodTest.section(layout);
        InputMethodTest.layout(layout);
        InputMethodTest.setCapsLockState(true);
        InputMethodTest.type(VK_A, 0);
        InputMethodTest.type(VK_B, 0);
        InputMethodTest.type(VK_C, 0);
        InputMethodTest.expect(expectUppercase ? "ABC" : "abc");
        InputMethodTest.type(VK_ESCAPE, 0);
    }
}
