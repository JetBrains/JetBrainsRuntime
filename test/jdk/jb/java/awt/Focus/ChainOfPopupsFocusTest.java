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
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
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
 * Then the popups are closed one by one. Test fails if focus went somewhere else than the parent popup.
 */

public class ChainOfPopupsFocusTest implements Runnable, ActionListener {

    private static final int DEPTH = 5;
    private static final String FRAMENAME = "MainFrame";
    private static final String POPUPNAME = "Popup";

    private static volatile Component focusOwner;
    private static volatile int popupsCount;
    private static Robot robot;
    private JFrame frame;

    public static void main(String[] args) throws Exception {

        robot = new Robot();
        robot.setAutoDelay(50);

        ChainOfPopupsFocusTest test = new ChainOfPopupsFocusTest();
        SwingUtilities.invokeAndWait(test);
        robot.delay(1000);

        // Workaroud for JBR-2657 on Windows
        // Click on the main test frame, so it gets focus when running from background process,
        // otherwise it just flashes on the taskbar
        Point center = GraphicsEnvironment.getLocalGraphicsEnvironment().getCenterPoint();
        robot.mouseMove((int) center.getX(), (int) center.getY());
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(1000);

        try {
            SwingUtilities.invokeAndWait(() ->
                    focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            if(focusOwner == null || !focusOwner.getName().equals(FRAMENAME)) {
                throw new RuntimeException("Test ERROR: " + FRAMENAME + " is not focused");
            }

            for (int count = 1; count <= DEPTH; count++) {
                pressCtrlKey(KeyEvent.VK_M);
                robot.delay(1000);
            }

            if(popupsCount != DEPTH) {
                throw new RuntimeException("Test ERROR: Number of open popups is "
                        + popupsCount + ", but " + DEPTH + " is expected");
            }
            for (int count = DEPTH-1; count >= 0; count--) {
                pressCtrlKey(KeyEvent.VK_X);
                robot.delay(1000);
                SwingUtilities.invokeAndWait(() ->
                        focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                String focusedComponent = (focusOwner != null ? focusOwner.getName() : "Nothing");
                String expectedComponent = (count == 0 ? FRAMENAME : POPUPNAME + count);
                if(!focusedComponent.equals(expectedComponent)) {
                    throw new RuntimeException("Test FAILED: "
                            + focusedComponent + " is focused instead of " + expectedComponent);
                }
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
        JTextArea area = createTextArea(FRAMENAME);
        frame = new JFrame(getClass().getSimpleName());
        frame.add(new JScrollPane(area));
        frame.pack();
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
    }

    private JTextArea createTextArea(String locatedOn) {
        JTextArea area = new JTextArea(20, 40);
        area.setName(locatedOn);
        area.registerKeyboardAction(this, "show", KeyStroke.getKeyStroke("control M"), JComponent.WHEN_FOCUSED);
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
}
