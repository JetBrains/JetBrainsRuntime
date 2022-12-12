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

/**
 * @test
 * @summary Regression test for JBR-5095 Incorrect initial window's location under GNOME
 * @key headful
 */

public class WindowInitialPosition {
    private static JFrame frame;
    private static Point location;
    private static Point locationOnScreen;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(WindowInitialPosition::initUI);
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(WindowInitialPosition::fetchLocation);
            Point expectedLocation = new Point(200, 200);
            if (!expectedLocation.equals(location)) {
                throw new RuntimeException("Frame reports unexpected location: " + location);
            }
            if (!expectedLocation.equals(locationOnScreen)) {
                throw new RuntimeException("Frame reports unexpected locationOnScreen: " + locationOnScreen);
            }
        }
        finally {
            SwingUtilities.invokeAndWait(WindowInitialPosition::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("WindowInitialPosition");
        frame.setBounds(200, 200, 200, 200);
        frame.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void fetchLocation() {
        location = frame.getLocation();
        locationOnScreen = frame.getLocationOnScreen();
    }
}
