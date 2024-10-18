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

import java.awt.*;
import javax.swing.*;

/**
 * @test
 * @summary Regression test for JBR-5189 Can't exit fullscreen mode on ubuntu 22.10
 * @key headful
 */

public class RestoreFromFullScreen {
    private static final int INITIAL_WIDTH = 400;
    private static final int INITIAL_HEIGHT = 300;

    private static JFrame frame;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(RestoreFromFullScreen::initUI);
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> frame.getGraphicsConfiguration().getDevice().setFullScreenWindow(frame));
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> {
                if (frame.getWidth() <= INITIAL_WIDTH || frame.getHeight() <= INITIAL_HEIGHT) {
                    throw new RuntimeException("Full screen transition hasn't happened");
                }
                frame.getGraphicsConfiguration().getDevice().setFullScreenWindow(null);
            });
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> {
                if (frame.getWidth() != INITIAL_WIDTH || frame.getHeight() != INITIAL_HEIGHT) {
                    throw new RuntimeException("Restoring of the original size hasn't happened");
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(RestoreFromFullScreen::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("RestoreFromFullScreen");
        frame.setBounds(200, 200, INITIAL_WIDTH, INITIAL_HEIGHT);
        frame.setUndecorated(true);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }
}
