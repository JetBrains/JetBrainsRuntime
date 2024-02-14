/*
 * Copyright (c) 2000-2024 JetBrains s.r.o.
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
 * @summary Regression test for JBR-6704 Extra IME event fired when pressing a keystroke containing Ctrl and focus moving to a different window
 * @modules java.desktop/sun.lwawt.macosx
 * @run main InputMethodTest CtrlShortcutNewWindowTest
 * @requires (jdk.version.major >= 8 & os.family == "mac")
 */

import javax.swing.*;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import static java.awt.event.KeyEvent.*;

public class CtrlShortcutNewWindowTest implements Runnable {
    @Override
    public void run() {
        var frame = new JFrame();
        frame.setSize(300, 300);
        frame.setLocation(100, 100);
        JTextField textField = new JTextField();
        frame.add(textField);
        textField.requestFocusInWindow();

        final boolean[] keyPressed = {false};

        InputMethodTest.textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == VK_BACK_QUOTE && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {
                    keyPressed[0] = true;
                    frame.setVisible(true);
                }
            }
        });

        InputMethodTest.section("Ctrl+Backtick");
        InputMethodTest.layout("com.apple.keylayout.ABC");
        InputMethodTest.type(VK_BACK_QUOTE, CTRL_DOWN_MASK);
        InputMethodTest.delay(500);

        if (!keyPressed[0]) {
            InputMethodTest.fail("Ctrl+Backtick key combination not detected");
        }

        if (!textField.getText().isEmpty()) {
            InputMethodTest.fail("Extra characters in the text field");
        }

        frame.setVisible(false);
        frame.dispose();
    }
}
