/*
 * Copyright 2021 JetBrains s.r.o.
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

import com.apple.eawt.Application;

import java.awt.Window;
import javax.swing.*;

/**
 * @test
 * @summary Regression test for JBR-3666 Child window stays on default space
 *          when full-screen mode is activated for parent window on macOS
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 * @compile MacSpacesUtil.java
 * @run main FullScreenChildWindowShownBefore
 */

public class FullScreenChildWindowShownBefore {
    private static JFrame frame;
    private static JDialog dialog;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(FullScreenChildWindowShownBefore::initUI);
            Thread.sleep(1000); // wait for windows to appear
            SwingUtilities.invokeAndWait(() -> Application.getApplication().requestToggleFullScreen(frame));
            Thread.sleep(1000); // wait for transition to full screen to finish
            ensureVisible(frame, 250, 150);
            ensureVisible(dialog, 250, 250);
            SwingUtilities.invokeAndWait(() -> Application.getApplication().requestToggleFullScreen(frame));
            Thread.sleep(1000); // wait for transition from full screen to finish
            ensureVisible(frame, 250, 150);
            ensureVisible(dialog, 250, 250);
        } finally {
            SwingUtilities.invokeAndWait(FullScreenChildWindowShownBefore::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("FullScreenChildWindowShownBefore");
        frame.setBounds(100, 100, 300, 300);
        frame.setVisible(true);

        dialog = new JDialog(frame, false);
        dialog.setBounds(200, 200, 100, 100);
        dialog.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void ensureVisible(Window window, int x, int y) throws Exception {
        if (!MacSpacesUtil.isWindowVisibleAtPoint(window, x, y)) throw new RuntimeException(window + " isn't visible");
    }
}
