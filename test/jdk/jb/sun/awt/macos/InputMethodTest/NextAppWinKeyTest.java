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
 * @run main/othervm -Dapple.awt.captureNextAppWinKey=true -Dcom.sun.awt.reportDeadKeysAsNormal=false NextAppWinKeyTest dead
 * @run main/othervm -Dapple.awt.captureNextAppWinKey=true -Dcom.sun.awt.reportDeadKeysAsNormal=true NextAppWinKeyTest normal
 * @requires (jdk.version.major >= 17 & os.family == "mac")
 */

import javax.swing.*;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import static java.awt.event.KeyEvent.*;

public class NextAppWinKeyTest extends TestFixture {
    private final boolean deadKeys;

    public NextAppWinKeyTest(boolean deadKeys) {
        this.deadKeys = deadKeys;
    }

    @Override
    public void test() throws Exception {
        final JFrame[] extraFrame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            extraFrame[0] = new JFrame();
            extraFrame[0].setAutoRequestFocus(false);
            extraFrame[0].setSize(300, 300);
            extraFrame[0].setLocation(100, 100);
            extraFrame[0].setVisible(true);
            extraFrame[0].addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent focusEvent) {
                    expect(false, "Focus switched to the other window");
                }

                @Override
                public void focusLost(FocusEvent focusEvent) {}
            });
        });

        section("ABC");
        layout("com.apple.keylayout.ABC");
        press(VK_BACK_QUOTE, META_DOWN_MASK);
        expectKeyPressed(VK_BACK_QUOTE, META_DOWN_MASK);

        section("US-Intl");
        layout("com.apple.keylayout.USInternational-PC");
        press(VK_BACK_QUOTE, META_DOWN_MASK);
        expectKeyPressed(deadKeys ? VK_DEAD_GRAVE : VK_BACK_QUOTE, META_DOWN_MASK);

        section("French");
        layout("com.apple.keylayout.French");
        press(VK_BACK_SLASH, META_DOWN_MASK);
        expectKeyPressed(deadKeys ? VK_DEAD_GRAVE : VK_BACK_QUOTE, META_DOWN_MASK);

        SwingUtilities.invokeAndWait(() -> {
            extraFrame[0].setVisible(false);
            extraFrame[0].dispose();
        });
    }

    public static void main(String[] args) {
        assert args.length == 1;
        new NextAppWinKeyTest(args[0].equals("dead")).run();
    }
}
