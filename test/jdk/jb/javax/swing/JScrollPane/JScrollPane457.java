/*
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

/*
 * Description: The test creates <code>JScrollPane</code> that displays <code>TextArea</code> with text which contains
 * huge number of lines which can be specified via <code>LINE_NUMBER</code>
 * <code>NUMBER_OF_ITERATIONS</code>
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
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.DefaultCaret;

import java.awt.Robot;
import java.lang.reflect.InvocationTargetException;

public class JScrollPane457 extends JPanel implements Runnable {

    private static Robot robot;
    private static JFrame frame;
    private static JScrollPane457 test;
    private static JTextArea textArea;

    private static int NUMBER_OF_ITERATIONS = 100;
    private static int NUMBER_OF_LINES = 100000;
    private static final int ROBOT_DELAY = 200;

    private static final Object testCompleted = new Object();

    public void run() {
        robot.delay(ROBOT_DELAY);
        textArea.setCaretPosition(position);
    }

    private int position;
    private void doTest() throws InvocationTargetException, InterruptedException {

        for (int j = 0; j < NUMBER_OF_ITERATIONS; j++) {
            position = textArea.getDocument().getStartPosition().getOffset();
            SwingUtilities.invokeAndWait(test);

            position =textArea.getDocument().getEndPosition().getOffset() - 1;
            SwingUtilities.invokeAndWait(test);
        }

    }

    private static void createAndShowGUI() {
        test = new JScrollPane457();
        test.setBorder(new TitledBorder(new EtchedBorder(), "Text Area"));

        textArea = new JTextArea(25, 80);
        textArea.setEditable(false);

        for (int i = 0; i < NUMBER_OF_LINES; i++) {
            textArea.append("line " + i + " 1234567890qwertyuiopasdfghjklzxcvbnm 1234567890qwertyuiopasdfghjklzxcvbnm\n");
        }

        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        test.add(scroll);

        DefaultCaret caret = (DefaultCaret) textArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        frame = new JFrame("OGLTR_DisableGlyphModeState test");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1000, 1000);
        frame.add(test);

        frame.pack();
        frame.setVisible(true);
    }

    private static void disposeGUI() {
        frame.setVisible(false);
        frame.dispose();
    }

        public static void main(String[] args) throws Exception {
        robot = new Robot();
        if (args.length > 0)
            JScrollPane457.NUMBER_OF_ITERATIONS = Integer.parseInt(args[0]);
        if (args.length > 1)
            JScrollPane457.NUMBER_OF_LINES = Integer.parseInt(args[1]);

        synchronized (testCompleted) {
            SwingUtilities.invokeAndWait(JScrollPane457::createAndShowGUI);
            test.doTest();
            SwingUtilities.invokeAndWait(JScrollPane457::disposeGUI);
        }
    }
}