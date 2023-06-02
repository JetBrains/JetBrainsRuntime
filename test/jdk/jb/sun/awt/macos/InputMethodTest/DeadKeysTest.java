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
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest DeadKeysTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import static java.awt.event.KeyEvent.*;

public class DeadKeysTest implements Runnable {
    static private final int VK_SECTION = 0x01000000+0x00A7;
    @Override
    public void run() {
        InputMethodTest.layout("com.apple.keylayout.ABC");

        InputMethodTest.section("ABC: Acute accent + vowel");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_A, 0);
        InputMethodTest.expectText("\u00e1");

        InputMethodTest.section("ABC: Acute accent + consonant");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_S, 0);
        InputMethodTest.expectText("\u00b4s");

        InputMethodTest.section("ABC: Acute accent + space");
        InputMethodTest.type(VK_E, ALT_DOWN_MASK);
        InputMethodTest.type(VK_SPACE, 0);
        InputMethodTest.expectText("\u00b4");

        InputMethodTest.section("German - Standard: Opt+K, Section = Dead circumflex below");
        InputMethodTest.layout("com.apple.keylayout.German-DIN-2137");
        InputMethodTest.type(VK_K, ALT_DOWN_MASK);
        InputMethodTest.type(VK_SECTION, 0);
        InputMethodTest.type(VK_D, 0);
        InputMethodTest.expectText("\u1e13");

        InputMethodTest.section("UnicodeHexInput: U+0041 = A");
        InputMethodTest.layout("com.apple.keylayout.UnicodeHexInput");
        InputMethodTest.type(VK_0, ALT_DOWN_MASK);
        InputMethodTest.type(VK_0, ALT_DOWN_MASK);
        InputMethodTest.type(VK_4, ALT_DOWN_MASK);
        InputMethodTest.type(VK_1, ALT_DOWN_MASK);
        InputMethodTest.expectText("A");
    }
}
