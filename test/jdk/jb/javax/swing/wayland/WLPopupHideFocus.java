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

import jtreg.SkippedException;

import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @summary JBR-10583 Popup windows correctly transfer their focus upon hiding
 * @requires os.family == "linux"
 * @library /test/lib
 * @build jtreg.SkippedException
 * @key headful
 * @run main/othervm WLPopupHideFocus
 */
public class WLPopupHideFocus {
    static Robot robot;
    static JFrame toplevel;
    static JWindow popup1;
    static JWindow popup2;

    static boolean isFocused(Window window) throws InterruptedException, InvocationTargetException {
        boolean[] result = new boolean[]{false};
        SwingUtilities.invokeAndWait(() -> {
            result[0] = window.isFocused();
        });
        return result[0];
    }

    static JWindow newPopup(Window owner, String text) {
        JWindow popup = new JWindow(owner);
        popup.setType(Window.Type.POPUP);
        popup.setName(text);
        popup.add(new JList<>(new String[]{text}));
        popup.pack();
        return popup;
    }

    public static void main(String[] args) throws AWTException, InterruptedException, InvocationTargetException {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(() -> {
                toplevel = new JFrame("WLPopupHideFocus");
                toplevel.setSize(200, 200);
                toplevel.add(new JList<>(new String[]{"toplevel"}));
                toplevel.setVisible(true);
            });
            robot.waitForIdle();

            if (!isFocused(toplevel)) {
                throw new SkippedException("toplevel is not the focused window");
            }

            testNestedPopups();
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (popup1 != null) {
                    popup1.dispose();
                }
                if (popup2 != null) {
                    popup2.dispose();
                }
                if (toplevel != null) {
                    toplevel.dispose();
                }
            });
        }
    }

    static void testNestedPopups() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            popup1 = newPopup(toplevel, "popup1");
            popup1.setVisible(true);
            popup2 = newPopup(popup1, "popup2");
            popup2.setVisible(true);
        });
        robot.waitForIdle();
        SwingUtilities.invokeAndWait(() -> {
            popup2.requestFocus();
        });
        robot.waitForIdle();
        if (!isFocused(popup2)) {
            throw new RuntimeException("popup2 is not the focused window after explicit request");
        }

        SwingUtilities.invokeAndWait(() -> {
            popup2.setVisible(false);
            popup1.setVisible(false);
        });
        robot.waitForIdle();
        if (!isFocused(toplevel)) {
            throw new RuntimeException("toplevel is not the focused window after popups closed");
        }
    }
}
