/*
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

/* @test
 * @summary Verifies that an iconified window is restored with Window.toFront()
 * @key headful
 * @requires (os.name == "linux")
 * @library /test/lib
 * @run main IconifiedToFront
 */
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Robot;

public class IconifiedToFront {
    private static final int PAUSE_MS = 500;
    private static Robot robot;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        SwingUtilities.invokeAndWait(IconifiedToFront::test1);
        SwingUtilities.invokeAndWait(IconifiedToFront::test2);
        SwingUtilities.invokeAndWait(IconifiedToFront::test3);
        SwingUtilities.invokeAndWait(IconifiedToFront::test4);
    }

    private static void test1() {
        JFrame frame1 = new JFrame("IconifiedToFront Test 1");
        try {
            frame1.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame1.setSize(400, 300);
            frame1.setVisible(true);
            pause();
            frame1.setExtendedState(JFrame.ICONIFIED);
            pause();
            frame1.toFront();
            pause();
            int state = frame1.getExtendedState();
            if ((state & JFrame.ICONIFIED) != 0) {
                throw new RuntimeException("Test Failed: state is still ICONIFIED: " + state);
            }
        } finally {
            frame1.dispose();
        }
    }

    private static void test2() {
        JFrame frame1 = new JFrame("IconifiedToFront Test 2");
        try {
            frame1.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame1.setSize(400, 300);
            frame1.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame1.setVisible(true);
            pause();
            frame1.setExtendedState(JFrame.ICONIFIED | JFrame.MAXIMIZED_BOTH);
            pause();
            frame1.toFront();
            pause();
            int state = frame1.getExtendedState();
            if ((state & JFrame.ICONIFIED) != 0) {
                throw new RuntimeException("Test Failed: state is still ICONIFIED: " + state);
            }
        } finally {
            frame1.dispose();
        }
    }

    private static void test3() {
        JFrame frame1 = new JFrame("IconifiedToFront Test 3");
        try {
            frame1.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame1.setSize(400, 300);
            frame1.setUndecorated(true);
            frame1.setVisible(true);
            pause();
            frame1.setExtendedState(JFrame.ICONIFIED);
            pause();
            frame1.toFront();
            pause();
            int state = frame1.getExtendedState();
            if ((state & JFrame.ICONIFIED) != 0) {
                throw new RuntimeException("Test Failed: state is still ICONIFIED: " + state);
            }
        } finally {
            frame1.dispose();
        }
    }

    private static void test4() {
        JFrame frame1 = new JFrame("IconifiedToFront Test 4");
        try {
            frame1.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame1.setSize(400, 300);
            frame1.setUndecorated(true);
            frame1.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame1.setVisible(true);
            pause();
            frame1.setExtendedState(JFrame.ICONIFIED | JFrame.MAXIMIZED_BOTH);
            pause();
            frame1.toFront();
            pause();
            int state = frame1.getExtendedState();
            if ((state & JFrame.ICONIFIED) != 0) {
                throw new RuntimeException("Test Failed: state is still ICONIFIED: " + state);
            }
        } finally {
            frame1.dispose();
        }
    }

    private static void pause() {
        robot.delay(PAUSE_MS);
    }
}
