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
 * @summary Regression test for IDEA-221385: Cannot input with half-width punctuation.
 * @run shell Runner.sh --fullwidth PinyinFullWidthPunctuationTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class PinyinFullWidthPunctuationTest implements Runnable {
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.inputmethod.SCIM.ITABC");

        InputMethodTest.section("comma");
        InputMethodTest.type(VK_COMMA, 0);
        InputMethodTest.expect("\uff0c");

        InputMethodTest.section("period");
        InputMethodTest.type(VK_PERIOD, 0);
        InputMethodTest.expect("\u3002");

        InputMethodTest.section("question mark");
        InputMethodTest.type(VK_SLASH, SHIFT_DOWN_MASK);
        InputMethodTest.expect("\uff1f");

        InputMethodTest.section("semicolon");
        InputMethodTest.type(VK_SEMICOLON, 0);
        InputMethodTest.expect("\uff1b");

        InputMethodTest.section("colon");
        InputMethodTest.type(VK_SEMICOLON, SHIFT_DOWN_MASK);
        InputMethodTest.expect("\uff1a");

        InputMethodTest.section("left square bracket");
        InputMethodTest.type(VK_OPEN_BRACKET, 0);
        InputMethodTest.expect("\u3010");

        InputMethodTest.section("right square bracket");
        InputMethodTest.type(VK_CLOSE_BRACKET, 0);
        InputMethodTest.expect("\u3011");
    }
}
