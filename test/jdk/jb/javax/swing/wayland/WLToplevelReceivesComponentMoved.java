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

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @summary JBR-10435 Verifies toplevels receive COMPONENT_MOVED when their locations get reset
 * @requires os.family == "linux"
 * @key headful
 * @run main/othervm WLToplevelReceivesComponentMoved
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLToplevelReceivesComponentMoved
 */
public class WLToplevelReceivesComponentMoved {
    static Robot robot;
    static JFrame frame;
    static volatile boolean didMove = false;

    public static void main(String[] args) throws AWTException, InterruptedException, InvocationTargetException {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        robot = new Robot();
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("WLToplevelReceivesComponentMoved");
            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent e) {
                    didMove = true;
                }
            });
            frame.setSize(200, 200);
            frame.setLocation(100, 100);
        });

        SwingUtilities.invokeAndWait(() -> {}); // drain EDT
        didMove = false;

        SwingUtilities.invokeAndWait(() -> {
            frame.setVisible(true);
        });

        robot.waitForIdle();
        SwingUtilities.invokeAndWait(() -> {
            if (frame.getLocation().equals(new Point(100, 100))) {
                throw new RuntimeException("Frame didn't get snapped to (0, 0) of its device");
            }
            frame.dispose();
        });

        if (!didMove) {
            throw new RuntimeException("Frame didn't receive COMPONENT_MOVED");
        }
    }
}
