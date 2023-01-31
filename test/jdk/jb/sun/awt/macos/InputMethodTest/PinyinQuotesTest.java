/*
 * Copyright 2000-2022 JetBrains s.r.o.
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
