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
 * @summary Regression test for JBR-5379 Last character from Korean input gets inserted once again on click
 * @modules java.desktop/sun.lwawt.macosx
 * @run main FocusMoveUncommitedCharactersTest
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import javax.swing.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;

import static java.awt.event.KeyEvent.*;

public class FocusMoveUncommitedCharactersTest extends TestFixture {
    private JTextArea textArea2;
    private boolean typedEventReceived = false;

    @Override
    protected List<JComponent> getExtraComponents() {
        textArea2 = new JTextArea();

        textArea2.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                typedEventReceived = true;
            }
        });

        return List.of(textArea2);
    }

    @Override
    public void test() throws Exception {
        layout("com.apple.inputmethod.Korean.2SetKorean");
        press(VK_A);
        press(VK_K);

        var point = new Point(textArea2.getWidth() / 2, textArea2.getHeight() / 2);
        SwingUtilities.convertPointToScreen(point, textArea2);

        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        robot.delay(1000);

        expect(!typedEventReceived, "no KeyTyped events on the second text area");
        expect(textArea2.getText().isEmpty(), "second text area is empty");
    }

    public static void main(String[] args) {
        new FocusMoveUncommitedCharactersTest().run();
    }
}
