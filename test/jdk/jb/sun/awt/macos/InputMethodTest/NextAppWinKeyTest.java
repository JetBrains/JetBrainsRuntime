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
 * @summary Regression test for JBR-5469 Something weird going on with Cmd+Backtick / Cmd+Dead Grave
 * @modules java.desktop/sun.lwawt.macosx
 * @run main/othervm -Dapple.awt.captureNextAppWinKey=true InputMethodTest NextAppWinKeyTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import javax.swing.*;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import static java.awt.event.KeyEvent.*;

public class NextAppWinKeyTest implements Runnable {
    @Override
    public void run() {
        var extraFrame = new JFrame();
        extraFrame.setAutoRequestFocus(false);
        extraFrame.setSize(300, 300);
        extraFrame.setLocation(100, 100);
        extraFrame.setVisible(true);
        extraFrame.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent focusEvent) {
                InputMethodTest.fail("Focus switched to the other window");
            }

            @Override
            public void focusLost(FocusEvent focusEvent) {}
        });

        InputMethodTest.section("ABC");
        InputMethodTest.layout("com.apple.keylayout.ABC");
        InputMethodTest.type(VK_BACK_QUOTE, META_DOWN_MASK);
        InputMethodTest.expectKeyPress(VK_BACK_QUOTE, KEY_LOCATION_STANDARD, META_DOWN_MASK, false);

        InputMethodTest.section("US-Intl");
        InputMethodTest.layout("com.apple.keylayout.USInternational-PC");
        InputMethodTest.type(VK_BACK_QUOTE, META_DOWN_MASK);
        InputMethodTest.expectKeyPress(VK_DEAD_GRAVE, KEY_LOCATION_STANDARD, META_DOWN_MASK, false);

        InputMethodTest.section("French");
        InputMethodTest.layout("com.apple.keylayout.French");
        InputMethodTest.type(VK_BACK_SLASH, META_DOWN_MASK);
        InputMethodTest.expectKeyPress(VK_DEAD_GRAVE, KEY_LOCATION_STANDARD, META_DOWN_MASK, false);

        extraFrame.setVisible(false);
        extraFrame.dispose();
    }
}
