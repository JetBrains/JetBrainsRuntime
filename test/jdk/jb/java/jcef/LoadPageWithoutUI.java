// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 * @summary Regression test for JBR-2259. The test checks that website is loaded with and without showing Browser UI.
 * @run main LoadPageWithoutUI
 */


/**
 * Description:
 * The test detects callbacks of CefLoadHandler in the following cases:
 * 1. Before showing UI (before frame.setVisible(true) is called)
 * 2. With UI  (after frame.setVisible(true) was called)
 * 3. After disable showing UI (after frame.setVisible(false) was called)
 */

public class LoadPageWithoutUI {
    private static final String DUMMY = "file://" + System.getProperty("test.src") + "/dummy.html";
    private static final String BLANK = "about:blank";

    private CountDownLatch latch;
    private JBCefBrowser browser = new JBCefBrowser();
    private JFrame frame = new JFrame("JCEF");

    private volatile boolean loadHandlerUsed;

    public void initUI() {
        browser.getCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                System.out.println("onLoadingStateChange " + browser.getURL());
                loadHandlerUsed = true;
            }

            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
                System.out.println("onLoadStart " + browser.getURL());
                loadHandlerUsed = true;
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                System.out.println("onLoadEnd " + browser.getURL());
                loadHandlerUsed = true;
                latch.countDown();
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
                System.out.println("onLoadError " + browser.getURL());
                loadHandlerUsed = true;
            }
        });
        frame.getContentPane().add(browser.getCefBrowser().getUIComponent());
        frame.setSize(640, 480);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                browser.dispose();
            }
        });
    }

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {
        LoadPageWithoutUI test = new LoadPageWithoutUI();
        try {
            test.latch = new CountDownLatch(1);
            SwingUtilities.invokeLater(test::initUI);
            test.latch.await(5, TimeUnit.SECONDS);

            System.out.println("Loading URL " + BLANK + " before enabling browser UI...");
            test.latch = new CountDownLatch(1);
            test.browser.loadURL(BLANK);
            test.latch.await(5, TimeUnit.SECONDS);
            if (!test.loadHandlerUsed) {
                throw new RuntimeException(BLANK + " is not loaded without browser UI");
            }
            test.loadHandlerUsed = false;
            System.out.println(BLANK + " is loaded");

            System.out.println("Loading URL " + DUMMY + " after enabling browser UI...");
            SwingUtilities.invokeAndWait(() -> test.frame.setVisible(true));
            test.latch = new CountDownLatch(1);
            test.browser.loadURL(DUMMY);
            test.latch.await(5, TimeUnit.SECONDS);
            if (!test.loadHandlerUsed) {
                throw new RuntimeException(DUMMY + " is not loaded with browser UI");
            }
            test.loadHandlerUsed = false;
            System.out.println(DUMMY + " is loaded");

            System.out.println("Loading URL " + BLANK + " after disabling browser UI...");
            SwingUtilities.invokeAndWait(() -> test.frame.setVisible(false));
            test.latch = new CountDownLatch(1);
            test.browser.loadURL(BLANK);
            test.latch.await(5, TimeUnit.SECONDS);
            if (!test.loadHandlerUsed) {
                throw new RuntimeException(DUMMY + " is not loaded after disabling browser UI");
            }
            test.loadHandlerUsed = false;
            System.out.println(BLANK + " is loaded");

        } finally {
            test.browser.dispose();
            JBCefApp.getInstance().getCefApp().dispose();
            SwingUtilities.invokeAndWait(() -> test.frame.dispose());
        }
    }
}