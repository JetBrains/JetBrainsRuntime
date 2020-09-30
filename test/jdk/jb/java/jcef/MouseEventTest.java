// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @key headful
 * @summary Regression test for JBR-2412. The test checks that mouse actions are handled on jcef browser after hide and show it.
 * @run main/othervm MouseEventTest
 */

public class MouseEventTest {
    private static Robot robot;
    private static final int WIDTH = 400;
    private static final int HEIGHT = 400;
    private static CefBrowserFrame browserFrame = new CefBrowserFrame(WIDTH, HEIGHT);

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {

        try {
            robot = new Robot();
            SwingUtilities.invokeAndWait(browserFrame::initUI);
            robot.waitForIdle();

            doMouseActions();
            if (!browserFrame.isMouseActionPerformed()) {
                throw new RuntimeException("Test FAILED. Some of mouse actions were not handled.");

            }

            browserFrame.resetMouseActionsPerformedFlag();
            browserFrame.hideAndShowBrowser();
            robot.delay(100);

            doMouseActions();
            if (!browserFrame.isMouseActionPerformed()) {
                throw new RuntimeException("Test FAILED. Some of mouse actions were not handled.");
            }
            System.out.println("Test PASSED");
        } catch (AWTException e) {
            e.printStackTrace();
        } finally {
            browserFrame.getBrowser().dispose();
            JBCefApp.getInstance().getCefApp().dispose();
            SwingUtilities.invokeAndWait(browserFrame::dispose);
        }
    }

    private static void doMouseActions() {
        Point frameCenter = new Point(browserFrame.getLocationOnScreen().x + WIDTH /2,
                browserFrame.getLocationOnScreen().y + HEIGHT /2);
        robot.mouseMove(frameCenter.x, frameCenter.y);
        robot.delay(100);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);
        robot.mouseMove(frameCenter.x + 1, frameCenter.y);
        robot.delay(100);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(100);
        robot.mouseWheel(1);
        robot.delay(100);

    }
}


class CefBrowserFrame extends JFrame {
    private final JBCefBrowser browser = new JBCefBrowser();
    private final int width, height;
    private volatile boolean mouseActionPerformed = false;

    private MouseAdapter mouseAdapter = new MouseAdapter() {
        @Override
        public void mouseDragged(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseDragged");
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseMoved");
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseWheelMoved");
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseClicked");
        }

        @Override
        public void mousePressed(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mousePressed");
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseReleased");
        }

        @Override
        public void mouseEntered(MouseEvent e) {
            mouseActionPerformed = true;
            System.out.println("mouseEntered");
        }
    };

    public CefBrowserFrame(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void initUI() {
        browser.getComponent().addMouseMotionListener(mouseAdapter);
        browser.getComponent().addMouseListener(mouseAdapter);
        browser.getComponent().addMouseWheelListener(mouseAdapter);

        getContentPane().add(browser.getCefBrowser().getUIComponent());
        setSize(width, height);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                browser.dispose();
            }
        });
        setVisible(true);
    }

    public void hideAndShowBrowser() {
        Container parent = browser.getComponent().getParent();
        parent.remove(browser.getComponent());
        SwingUtilities.invokeLater(() -> parent.add(browser.getComponent()));
    }

    public JBCefBrowser getBrowser() {
        return browser;
    }

    public void resetMouseActionsPerformedFlag() {
        mouseActionPerformed = false;
    }

    public boolean isMouseActionPerformed() {
        return mouseActionPerformed;
    }
}