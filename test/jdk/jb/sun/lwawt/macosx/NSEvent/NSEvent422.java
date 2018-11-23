package NSEvent;/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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