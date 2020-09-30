// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;

/**
 * @test
 * @key headful
 * @summary Regression test for JBR-2639. The test checks that mouse wheel action is handled on jcef browser.
 * @run main/othervm MouseWheelEventTest
 */

public class MouseWheelEventTest {

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {
        final int width = 400;
        final int height = 400;
        CefBrowserFrame browserFrame = new CefBrowserFrame(width, height);

        try {
            Robot r = new Robot();
            SwingUtilities.invokeAndWait(browserFrame::initUI);
            r.waitForIdle();

            r.mouseMove(browserFrame.getLocationOnScreen().x + width/2,
                    browserFrame.getLocationOnScreen().y + height/2);
            r.delay(100);
            r.mouseWheel(1);
            r.delay(100);

            if (browserFrame.isMouseWheelMoved()) {
                System.out.println("Test PASSED");
            } else {
                throw new RuntimeException("Test FAILED. Mouse wheel action was not handled.");
            }
        } catch (AWTException e) {
            e.printStackTrace();
        } finally {
            browserFrame.getBrowser().dispose();
            JBCefApp.getInstance().getCefApp().dispose();
            SwingUtilities.invokeAndWait(browserFrame::dispose);
        }
    }
}


class CefBrowserFrame extends JFrame {

    private volatile boolean mouseWheelMoved = false;
    private final JBCefBrowser browser = new JBCefBrowser();
    private int width, height;

    public CefBrowserFrame(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void initUI() {
        browser.getComponent().addMouseWheelListener(new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                mouseWheelMoved = true;
                System.out.println("mouseWheelMoved");
            }
        });
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

    public JBCefBrowser getBrowser() {
        return browser;
    }

    public boolean isMouseWheelMoved() {
        return mouseWheelMoved;
    }
}