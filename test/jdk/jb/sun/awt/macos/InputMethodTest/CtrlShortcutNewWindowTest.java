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
 * @run main CtrlShortcutNewWindowTest
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import javax.swing.*;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import static java.awt.event.KeyEvent.*;

public class CtrlShortcutNewWindowTest extends TestFixture {
    @Override
    public void test() throws Exception {
        final JFrame[] frame = {null};
        final JTextField[] textField = {null};
        final boolean[] keyPressed = {false};

        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("frame 2");
            frame[0].setSize(300, 300);
            frame[0].setLocation(100, 100);
            textField[0] = new JTextField();
            frame[0].add(textField[0]);
            textField[0].requestFocusInWindow();

            inputArea.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == VK_BACK_QUOTE && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {
                        keyPressed[0] = true;
                        frame[0].setVisible(true);
                    }
                }
            });
        });

        section("Ctrl+Backtick");
        layout("com.apple.keylayout.ABC");
        press(VK_BACK_QUOTE, CTRL_DOWN_MASK);
        delay(500);

        expect(keyPressed[0], "Ctrl+Backtick pressed");

        String[] text = {null};
        SwingUtilities.invokeAndWait(() -> {
            text[0] = textField[0].getText();
        });

        expect(text[0].isEmpty(), "Second text field empty");

        SwingUtilities.invokeAndWait(() -> {
            frame[0].setVisible(false);
            frame[0].dispose();
        });
    }

    public static void main(String[] args) {
        new CtrlShortcutNewWindowTest().run();
    }
}
