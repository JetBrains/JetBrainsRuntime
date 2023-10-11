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
 * @run main ExtendedFrameState
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

public class ExtendedFrameState {
    static final int DELAY_MS = 1000;
    JFrame frame;
    Robot robot = new Robot();

    public static void main(String[] args) throws Exception {
        var toolkit = Toolkit.getDefaultToolkit();
        if (toolkit.isFrameStateSupported(Frame.MAXIMIZED_HORIZ)
                && toolkit.isFrameStateSupported(Frame.MAXIMIZED_VERT)
                && toolkit.isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            var test = new ExtendedFrameState();

            try {
                test.prepare();
                test.execute();
            } finally {
                test.dispose();
            }
        }
    }

    ExtendedFrameState() throws Exception {
    }

    void prepare() {
        runSwing(() -> {
            frame = new JFrame("ExtendedFrameState");
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

        // NORMAL -> MAXIMIZED_VERT
        setState(Frame.MAXIMIZED_VERT);
        assertStateIs(Frame.MAXIMIZED_VERT);

        // MAXIMIZED_VERT -> NORMAL
        setState(Frame.NORMAL);
        assertStateIs(Frame.NORMAL);

        // NORMAL -> MAXIMIZED_HORIZ
        setState(Frame.MAXIMIZED_HORIZ);
        assertStateIs(Frame.MAXIMIZED_HORIZ);

        // MAXIMIZED_HORIZ -> MAXIMIZED_VERT
        // This transition is not supported by the WM for some obscure reason
        //setState(Frame.MAXIMIZED_VERT);
        //assertStateIs(Frame.MAXIMIZED_VERT);

        // MAXIMIZED_VERT -> MAXIMIZED_HORIZ
        setState(Frame.MAXIMIZED_HORIZ);
        assertStateIs(Frame.MAXIMIZED_HORIZ);

        // MAXIMIZED_HORIZ -> NORMAL
        setState(Frame.NORMAL);
        assertStateIs(Frame.NORMAL);

        // NORMAL -> MAXIMIZED_BOTH
        setState(Frame.MAXIMIZED_BOTH);
        assertStateIs(Frame.MAXIMIZED_BOTH);

        // MAXIMIZED_BOTH -> MAXIMIZED_HORIZ
        setState(Frame.MAXIMIZED_HORIZ);
        assertStateIs(Frame.MAXIMIZED_HORIZ);

        // MAXIMIZED_HORIZ -> MAXIMIZED_BOTH
        setState(Frame.MAXIMIZED_BOTH);
        assertStateIs(Frame.MAXIMIZED_BOTH);

        // MAXIMIZED_BOTH -> MAXIMIZED_VERT
        setState(Frame.MAXIMIZED_VERT);
        assertStateIs(Frame.MAXIMIZED_VERT);

        // MAXIMIZED_VERT -> NORMAL
        setState(Frame.NORMAL);
        assertStateIs(Frame.NORMAL);
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
