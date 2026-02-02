/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

import java.awt.Color;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.SystemColor;

/*
 * @test
 * @summary Verifies Window.setBackground() works correctly in various scenarios
 * @key headful
 * @run main WLSetBackground
 */
public class WLSetBackground {
    final static int PAUSE_MS = 500;

    public static void main(String[] args)  throws Exception {
        System.out.println("Color: default, size 500x500");
        Robot r = new Robot();
        Frame window = new Frame();
        window.setUndecorated(true);
        window.setVisible(true);
        window.setSize(500, 500);
        check(r, SystemColor.window);

        System.out.println("Color: blue, size 100x500");
        window.setVisible(false);
        window.setBackground(Color.BLUE);
        window.setSize(100, 500);
        window.setVisible(true);
        check(r, Color.BLUE);

        System.out.println("Color: green, size 200x200");
        window.setSize(200, 200);
        window.setBackground(Color.GREEN);
        window.repaint();
        check(r, Color.GREEN);

        System.out.println("Color: red, size 200x200");
        window.setBackground(Color.RED);
        window.repaint();
        check(r, Color.RED);
    }

    public static void check(Robot r, Color expectedBgColor) {
        r.waitForIdle();
        r.delay(PAUSE_MS);

        Color c = r.getPixelColor(50, 50);
        System.out.println("Pixel color @(50, 50): " + c);
        if (!c.equals(expectedBgColor)) {
            throw new RuntimeException("Expected background color: " + expectedBgColor + ", actual: " + c);
        }
    }
}
