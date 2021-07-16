/*
 * Copyright 2022 JetBrains s.r.o.
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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Regression test for JBR-4186 Unexpected desktop switch after moving a window to another desktop
 * @key headful
 * @requires (os.family == "mac")
 * @compile MacSpacesUtil.java
 * @run main ParentMovingToAnotherSpace
 */

public class ParentMovingToAnotherSpace {
    private static JFrame frame1;
    private static JFrame frame2;

    public static void main(String[] args) throws Exception {
        MacSpacesUtil.ensureMoreThanOneSpaceExists();
        try {
            SwingUtilities.invokeAndWait(ParentMovingToAnotherSpace::initUI);
            Thread.sleep(1000); // wait for windows to appear
            MacSpacesUtil.moveWindowToNextSpace(frame2);
            if (MacSpacesUtil.isWindowVisible(frame1)) {
                throw new RuntimeException("Space switch didn't work");
            }
            if (!MacSpacesUtil.isWindowVisible(frame2)) {
                throw new RuntimeException("Frame isn't showing when expected");
            }
        } finally {
            MacSpacesUtil.switchToPreviousSpace();
            SwingUtilities.invokeAndWait(ParentMovingToAnotherSpace::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("ParentMovingToAnotherSpace-1");
        frame1.setBounds(100, 100, 300, 100);
        frame1.setVisible(true);

        frame2 = new JFrame("ParentMovingToAnotherSpace-2");
        frame2.setBounds(100, 300, 300, 100);
        frame2.setVisible(true);

        JWindow window = new JWindow(frame2);
        window.setBounds(100, 500, 300, 100);
        window.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
    }
}
