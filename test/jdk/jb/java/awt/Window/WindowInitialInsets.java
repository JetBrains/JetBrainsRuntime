/*
 * Copyright 2026 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;

/**
 * @test
 * @summary Regression test for JBR-10066 Wrong insets reported after packing a window on Ubuntu
 * @key headful
 */

public class WindowInitialInsets {
    private static JFrame frame;
    private static Insets initialInsets;
    private static Insets finalInsets;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(WindowInitialInsets::initUI);
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(WindowInitialInsets::fetchInsets);
            if (!finalInsets.equals(initialInsets)) {
                throw new RuntimeException("Unexpected insets change: initial=" + initialInsets + ", final=" + finalInsets);
            }
        }
        finally {
            SwingUtilities.invokeAndWait(WindowInitialInsets::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("WindowInitialInsets");
        frame.pack();
        initialInsets = frame.getInsets();
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void fetchInsets() {
        finalInsets = frame.getInsets();
    }
}
