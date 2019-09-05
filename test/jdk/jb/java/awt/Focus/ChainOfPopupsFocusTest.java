/*
 * Copyright 2000-2019 JetBrains s.r.o.
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

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.FocusTraversalPolicy;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;

/**
 * @test
 * @summary Regression test for JBR-1417: JBR 11 does not support chain of popups
 * @requires (jdk.version.major >= 8)
 * @run main ChainOfPopupsFocusTest
 */

/*
 * Description: Test checks that focus goes to the parent popup when child popup is closed.
 * Test opens several popups one by one, setting current popup as a parent for the next one.
 * Then the popups are closed one by one. Test fails if the main frame gets closed or
 * if the number of closed popups is not equal to the number of open popups.
 * It means that focus went somewhere else than the parent popup.
 */

public class ChainOfPopupsFocusTest implements Runnable, ActionListener {

    private static final int DEPTH = 5;

    private static volatile boolean frameHidden = false;
    private static volatile int popupsHidden = 0;
    private static volatile Component focusOwner;
    private static Robot robot;
    private JFrame frame;

    public static void main(String[] args) throws Exception {

        ChainOfPopupsFocusTest test = new ChainOfPopupsFocusTest();
        SwingUtilities.invokeAndWait(test);

        robot = new Robot();
        robot.setAutoDelay(50);

        for (int i = 0; i < DEPTH; i++) pressCtrlKey(KeyEvent.VK_N);
        robot.delay(2000);
        for (int i = 0; i < DEPTH; i++) pressCtrlKey(KeyEvent.VK_X);
        robot.delay(2000);

        try {
            if(frameHidden) {
                System.err.println("Error: frameHidden=" + frameHidden);
                throw new RuntimeException("Test FAILED: Focus went to the main frame instead of the parent popup");
            }
            if(popupsHidden != DEPTH) {
                System.err.println("Error: frameHidden=" + frameHidden + " popupsHidden=" + popupsHidden);
                SwingUtilities.invokeAndWait(() -> focusOwner = test.frame.getFocusOwner());
                throw new RuntimeException("Test FAILED: Focus went to " + focusOwner + " instead of the parent popup");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> test.frame.dispose());
            robot.delay(2000);
        }
        System.out.println("Test PASSED");
    }

    private static void pressCtrlKey(int vk) {
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(vk);
        robot.keyRelease(vk);
        robot.keyRelease(KeyEvent.VK_CONTROL);
    }

    @Override
    public void run() {
        frame = new JFrame(getClass().getSimpleName());
        frame.add(new JScrollPane(createTextArea()));
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent event) {
                frameHidden = true;
            }
        });
        frame.setVisible(true);
    }

    private JTextArea createTextArea() {
        JTextArea area = new JTextArea(20, 40);
        area.registerKeyboardAction(this, "show", KeyStroke.getKeyStroke("control N"), JComponent.WHEN_FOCUSED);
        area.registerKeyboardAction(event -> SwingUtilities.getWindowAncestor((Component) event.getSource()).setVisible(false), KeyStroke.getKeyStroke("control X"), JComponent.WHEN_FOCUSED);
        area.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent event) {
                area.requestFocus();
            }
        });
        return area;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        Component source = (Component) event.getSource();
        switch (event.getActionCommand()) {
            case "show":
                Point point = source.getLocationOnScreen();
                Component area = createTextArea();
                Popup popup = PopupFactory.getSharedInstance().getPopup(source, new JScrollPane(area), point.x + 10, point.y + 10);
                Window window = SwingUtilities.getWindowAncestor(area);
                window.setAutoRequestFocus(true);
                window.setFocusable(true);
                window.setFocusableWindowState(true);
                window.setFocusTraversalPolicy(new FocusTraversalPolicy() {
                    @Override
                    public Component getComponentAfter(Container container, Component component) {
                        return area;
                    }

                    @Override
                    public Component getComponentBefore(Container container, Component component) {
                        return area;
                    }

                    @Override
                    public Component getFirstComponent(Container container) {
                        return area;
                    }

                    @Override
                    public Component getLastComponent(Container container) {
                        return area;
                    }

                    @Override
                    public Component getDefaultComponent(Container container) {
                        return area;
                    }
                });
                window.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentHidden(ComponentEvent event) {
                        popupsHidden++;
                    }
                });
                popup.show();
                break;
            case "hide":
                SwingUtilities.getWindowAncestor(source).setVisible(false);
                break;
        }
    }
}
