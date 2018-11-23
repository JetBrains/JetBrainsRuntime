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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/* @test
 * @summary regression test on
 * <ul>
 *     <li>JRE-401 AppCode freezes during autocomplete and other operations; and</li>
 *     <li>JRE-415 Mistake in "Merge with jdk8u152-b00"</li>
 * </ul>
 * @run main/othervm/timeout=360 Popup401 1000
 */

/*
 * Description: The test invoke <code>Popup.show()</code>/<code>hide()</code> methods <code>ITERATION_NUMBER</code>
 * times (by default 1000 times) and fails
 * <ul>
 *     <li>by <code>java.lang.RuntimeException: Waiting for the invocation event timed out</code> if it hangs because of
 *     the deadlock <code>at sun.lwawt.macosx.CPlatformComponent.$$YJP$$nativeCreateComponent(Native Method)</code> as
 *     it was described in JRE-401</li>
 *
 *     <li>or by <code>java.lang.RuntimeException: The test is near to be hang</code> if the method
 *     <code>Popup.show()</code> was executed <code>HANG_TIME_FACTOR</code> times longer than it was executed on the
 *     first iteration.
 * </ul>
 */
public class Popup401 extends JPanel {

    private JTextArea textArea;
    private JEditorPane editorPane;

    private static JFrame frame;
    private static Popup401 test;
    private static Robot robot;

    private static int ITERATION_NUMBER = 10;
    private static final int ROBOT_DELAY = 200;
    private static final int HANG_TIME_FACTOR = 10;

    private static final Object testCompleted = new Object();

    private Popup401() {
        textArea = new JTextArea("§1234567890-=\nqwertyuiop[]\nasdfghjkl;'\\\n`zxcvbnm,./\n");
        editorPane = new JEditorPane("text/html", "§1234567890-=\nqwertyuiop[]\nasdfghjkl;'\\\n`zxcvbnm,./\n");
        editorPane.setSize(300, 300);
        add(editorPane);
    }

    private void run() {
        JPanel panel = new JPanel();

        int count = 0;
        long diffTime, initialDiffTime = 0;
        while (count < ITERATION_NUMBER) {
            robot.delay(ROBOT_DELAY);

            PopupFactory factory = PopupFactory.getSharedInstance();
            Popup popup = factory.getPopup(panel, textArea, editorPane.getLocation().x + 20,
                    editorPane.getLocation().y + 20);

            long startTime = System.currentTimeMillis();
            popup.show();
            long endTime = System.currentTimeMillis();
            diffTime = endTime - startTime;

            if (count > 1) {
                if (diffTime * HANG_TIME_FACTOR < (endTime - startTime)) {
                    throw new RuntimeException("The test is near to be hang: iteration count = " + count
                            + " initial time = " + initialDiffTime
                            + " current time = " + diffTime);
                }
            } else {
                initialDiffTime = diffTime;
            }
            count++;
            robot.delay(ROBOT_DELAY);

            popup.hide();
        }
    }

    private static void createAndShowGUI() {
        frame = new JFrame("HangPopupTest");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1000, 1000);

        test = new Popup401();
        frame.add(test);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                super.componentShown(e);
                test.run();
                synchronized (testCompleted) {
                    testCompleted.notifyAll();
                }
            }
        });
        frame.pack();
        frame.setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        if (args.length > 0)
            Popup401.ITERATION_NUMBER = Integer.parseInt(args[0]);

        synchronized (testCompleted) {
            SwingUtilities.invokeAndWait(Popup401::createAndShowGUI);
            testCompleted.wait();
            frame.setVisible(false);
            frame.dispose();
        }
    }
}