/*
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

import java.awt.Robot;
import java.awt.Toolkit;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/* @test
 * @summary regression test on JRE-468 Idea freezes on project loading
  * @run main/othervm/timeout=360 GetScreenInsets468 100
 */

/*
 * Description: The test creates frames one by one and after each creation it calls
 * <code>Toolkit.getDefaultToolkit().getScreenInsets</code>. The number of  frames can be specified
 * by <code>ITERATION_NUMBER</code>, by default it equals <code>100</code>.
 */

public class GetScreenInsets468 implements Runnable {

    private static Robot robot;

    private static int ITERATION_NUMBER = 100;
    private static final int ROBOT_DELAY = 200;
    private static final int HANG_TIME_FACTOR = 10;

    private long initialDiffTime;
    private int count = 0;
    private JFrame frames[];

    public synchronized void run() {
        frames = new JFrame[ITERATION_NUMBER];

        while (count < ITERATION_NUMBER) {
            long startTime = System.currentTimeMillis();

            frames[count] = new JFrame("Hang getScreenInsets Test  " + count);
            frames[count].setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frames[count].setSize(350, 250);
            frames[count].setLocation(20 + count, 20 + count);

            frames[count].setVisible(true);
            robot.delay(ROBOT_DELAY);

            Toolkit.getDefaultToolkit().getScreenInsets(frames[count].getGraphicsConfiguration());

            long endTime = System.currentTimeMillis();
            long diffTime = endTime - startTime;

            System.out.println("count = " + count
                    + " initial time = " + initialDiffTime
                    + " current time = " + diffTime);
            if (count > 1) {
                if (initialDiffTime * HANG_TIME_FACTOR < diffTime) {
                    throw new RuntimeException("The test is near to be hang: iteration count = " + count
                            + " initial time = " + initialDiffTime
                            + " current time = " + diffTime);
                }
            } else {
                initialDiffTime = diffTime;
            }
            count++;
        }
    }

    private void disposeAll() {
        for (JFrame frame : frames) {
            frame.setVisible(false);
            frame.dispose();
        }
    }

    public static void main(String[] args) throws Exception {

        robot = new Robot();

        if (args.length > 0)
            GetScreenInsets468.ITERATION_NUMBER = Integer.parseInt(args[0]);

        GetScreenInsets468 test = new GetScreenInsets468();
        SwingUtilities.invokeAndWait(test);

        test.disposeAll();
    }
}