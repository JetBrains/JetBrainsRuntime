/*
 * Copyright 2023 JetBrains s.r.o.
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
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Regression test for JBR-2702 Tooltips display through other applications on hover
 * @key headful
 */

public class MouseMoveEventFallThroughTest {
    private static JFrame topFrame;
    private static JFrame bottomFrame;
    private static final AtomicBoolean bottomFrameReceivedMouseMoved = new AtomicBoolean();

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                bottomFrame = new JFrame("Bottom frame");
                bottomFrame.addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent e) {
                        bottomFrameReceivedMouseMoved.set(true);
                    }
                });
                bottomFrame.setSize(200, 100);
                bottomFrame.setLocation(250, 250);
                bottomFrame.setVisible(true);
                topFrame = new JFrame("Top frame");
                topFrame.setSize(300, 200);
                topFrame.setLocation(200, 200);
                topFrame.setVisible(true);
            });
            Robot robot = new Robot();
            robot.delay(1000);
            robot.mouseMove(350, 300); // position mouse cursor over both frames
            robot.delay(1000);
            SwingUtilities.invokeAndWait(() -> {
                bottomFrame.setSize(210, 110);
            });
            robot.delay(1000);
            robot.mouseMove(351, 301); // move mouse cursor a bit
            robot.delay(1000);
            if (bottomFrameReceivedMouseMoved.get()) {
                throw new RuntimeException("Mouse moved event dispatched to invisible window");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (topFrame != null) topFrame.dispose();
                if (bottomFrame != null) bottomFrame.dispose();
            });
        }
    }
}
