/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
package test.jb.testhelpers.utils;

import java.awt.Robot;
import java.awt.Window;

public class MouseUtils {

    public static void verifyLocationAndMove(Robot robot, Window window, int x, int y) {
        verifyLocation(window, x, y);

        robot.waitForIdle();
        robot.mouseMove(x, y);
        robot.delay(50);
    }

    public static void verifyLocationAndClick(Robot robot, Window window, int x, int y, int mask) {
        verifyLocation(window, x, y);

        robot.waitForIdle();
        robot.mouseMove(x, y);
        robot.delay(50);
        robot.mousePress(mask);
        robot.delay(50);
        robot.mouseRelease(mask);
    }

    private static void verifyLocation(Window window, int x, int y) {
        int x1 = window.getLocationOnScreen().x + window.getInsets().left;
        int x2 = x1 + window.getBounds().width - window.getInsets().right;
        int y1 = window.getLocationOnScreen().y + window.getInsets().top;
        int y2 = y1 + window.getBounds().height - window.getInsets().bottom;

        boolean isLocationValid = (x1 < x && x < x2 && y1 < y && y < y2);
        if (!isLocationValid) {
            throw new RuntimeException("Coordinates (" + x + ", " + y + ") is outside of clickable area");
        }
        if (!window.isVisible()) {
            throw new RuntimeException("Window isn't visible. Can't click to the area");
        }
    }

}