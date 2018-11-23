package NSEvent;/*
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

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Robot;
import java.awt.TextArea;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/*
 * Description: The class generates mouse movements.
 *
 */
public class NSEvent422 extends JPanel {

    private static Robot robot;
    private static JFrame staticFrame;
    private static final Object testCompleted = new Object();

    private static int ITERATION_NUMBER = 10;
    private static int MOVEMENTS_FOR_ONE_ITERATION = 100;

    private void doTest() {
        int xCoordA = 50;
        int yCoordA = 50;
        int xCoordB = 70;
        int yCoordB = 70;

        for (int i = 0; i < ITERATION_NUMBER; i++) {

            for (int j = 0; j < MOVEMENTS_FOR_ONE_ITERATION; j++) {
                robot.mouseMove(xCoordA, yCoordA);
                robot.mouseMove(xCoordB, yCoordB);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            fireAction(1000, () -> {
                System.gc();
            });
        }
    }

    private static void fireAction(int delay, Runnable action) {
        Timer t = new Timer(delay, (e) -> action.run());
        t.setRepeats(false);
        t.start();
    }

    private static void createAndShowGUI() {
        staticFrame = new JFrame("NSEvent422");
        staticFrame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        Component textArea = new TextArea();
        staticFrame.setSize(300, 300);
        staticFrame.add(textArea, BorderLayout.CENTER);

        NSEvent422 test = new NSEvent422();
        staticFrame.add(test);
        staticFrame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                super.componentShown(e);
                try {
                    test.doTest();
                } catch (OutOfMemoryError ex) {
                    ex.printStackTrace();
                    System.exit(1);
                } finally {
                    fireAction(1000, () -> {
                        synchronized (testCompleted) {
                            testCompleted.notifyAll();
                        }
                    });
                }
            }
        });
        staticFrame.pack();
        staticFrame.setVisible(true);
}

    public static void main(String[] args) throws Exception {
        if (args.length > 0)
            ITERATION_NUMBER = Integer.parseInt(args[0]);

        robot = new Robot();

        synchronized (testCompleted) {
            SwingUtilities.invokeAndWait(NSEvent422::createAndShowGUI);
            testCompleted.wait();
        }

        staticFrame.setVisible(false);
        staticFrame.dispose();
    }
}