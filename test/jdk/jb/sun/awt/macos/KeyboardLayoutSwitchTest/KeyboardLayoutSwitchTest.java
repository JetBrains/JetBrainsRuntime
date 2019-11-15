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

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

public class KeyboardLayoutSwitchTest implements Runnable {
    private static final String FRAMENAME = "MainFrame";

    private static final int DEPTH = 5;
    private static final String POPUPNAME = "Popup";

    private static volatile Component focusOwner;
    private static volatile int popupsCount;
    private static Robot robot;
    private JFrame frame;

    public static void main(String[] args) throws Exception {

        System.out.println("VK_TAB = " + KeyEvent.VK_TAB);
        robot = new Robot();
        robot.setAutoDelay(50);

        KeyboardLayoutSwitchTest test = new KeyboardLayoutSwitchTest();
        SwingUtilities.invokeAndWait(test);
        robot.delay(3000);

        try {
            SwingUtilities.invokeAndWait(() ->
                    pressCtrlKey(KeyEvent.VK_TAB));
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
//        JTextArea area = createTextArea(FRAMENAME);
        frame = new JFrame(getClass().getSimpleName());
//        frame.add(new JScrollPane(area));
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
    }
/*
    private JTextArea createTextArea(String locatedOn) {
        JTextArea area = new JTextArea(20, 40);
        area.setName(locatedOn);
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
                Component area = createTextArea(POPUPNAME + ++popupsCount);
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
                popup.show();
                break;
            case "hide":
                SwingUtilities.getWindowAncestor(source).setVisible(false);
                break;
        }
    }

 */
}