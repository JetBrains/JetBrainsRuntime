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

/* @test
 * @summary Verifies transitions between extended frame states
 * @requires os.family == "linux"
 * @run main ResizeUndecorated
 */

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.Toolkit;
import java.lang.reflect.InvocationTargetException;

public class ResizeUndecorated {
    static final int DELAY_MS = 1000;
    JFrame frame;
    Robot robot = new Robot();

    public static void main(String[] args) throws Exception {
        var toolkit = Toolkit.getDefaultToolkit();
        if (toolkit.isFrameStateSupported(Frame.MAXIMIZED_HORIZ)
                && toolkit.isFrameStateSupported(Frame.MAXIMIZED_VERT)
                && toolkit.isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            var test = new ResizeUndecorated();

            try {
                test.prepare();
                test.execute();
            } finally {
                test.dispose();
            }
        }
    }

    ResizeUndecorated() throws Exception {
    }

    void prepare() {
        runSwing(() -> {
            frame = new JFrame("Resize Undecorated Test");
            frame.setUndecorated(true);
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel panel = new JPanel();
            panel.add(new JLabel("label text"));
            frame.setContentPane(panel);
            frame.pack();
            frame.setVisible(true);
        });
    }

    void execute() {
        setState(Frame.NORMAL);
        assertStateIs(Frame.NORMAL);

        setState(Frame.MAXIMIZED_VERT);
        assertStateIs(Frame.MAXIMIZED_VERT);
        resizeFrame(10, 0);

        setState(Frame.NORMAL);
        assertStateIs(Frame.NORMAL);
        runSwing(() -> {
            frame.setBounds(100, 100, 200, 300);
        });

        robot.waitForIdle();
        robot.delay(DELAY_MS);

        setState(Frame.MAXIMIZED_HORIZ);
        assertStateIs(Frame.MAXIMIZED_HORIZ);
        resizeFrame(0, 20);
    }

    void resizeFrame(int dx, int dy) {
        var bounds = frame.getBounds();
        runSwing(() -> {
            frame.setBounds(bounds.x, bounds.y, bounds.width + dx, bounds.height + dy);
        });
        robot.waitForIdle();
        robot.delay(DELAY_MS);
        runSwing(() -> {
            var newBounds = frame.getBounds();
            if (bounds.width + dx != newBounds.width) {
                throw new RuntimeException(
                        String.format("Frame width after resize is %d, should be %d",
                                newBounds.width,
                                bounds.width + dx));
            }

            if (bounds.height + dy != newBounds.height) {
                throw new RuntimeException(
                        String.format("Frame height after resize is %d, should be %d",
                                newBounds.height,
                                bounds.height + dy));
            }
        });
    }

    void setState(int state) {
        runSwing(() -> {
            frame.setExtendedState(state);
        });
        robot.waitForIdle();
        robot.delay(DELAY_MS);
    }

    void assertStateIs(int idealState) {
        runSwing(() -> {
            int realState = frame.getExtendedState();
            if (idealState != realState) {
                throw new RuntimeException(
                        String.format("Expected frame state %d, got %d", idealState, realState));
            }
        });
    }

    void dispose() {
        runSwing(() -> {
            frame.dispose();
        });
    }

    private static void runSwing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
