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
 * @summary Regression test for JBR-5006 Dead keys exhibit invalid behavior on macOS
 * @run shell Runner.sh DeadKeysTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class DeadKeysTest implements Runnable {
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.keylayout.ABC");

        InputMethodTest.section("Acute accent + vowel");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_A, 0);
        InputMethodTest.expect("\u00e1");

        InputMethodTest.section("Acute accent + consonant");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_S, 0);
        InputMethodTest.expect("\u00b4s");

        InputMethodTest.section("Acute accent + space");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_SPACE, 0);
        InputMethodTest.expect("\u00b4");
    }
}
