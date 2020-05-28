// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/**
 * @test
 * @key headful
 * @summary Regression test for JBR-2430. The test checks that JS Query is handled in 2nd opened browser.
 * @run main/othervm HandleJSQueryTest
 */

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;

public class HandleJSQueryTest {

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {
        CefBrowserFrame firstBrowser = new CefBrowserFrame();
        CefBrowserFrame secondBrowser = new CefBrowserFrame();

        try {
            SwingUtilities.invokeLater(firstBrowser::initUI);
            Thread.sleep(3000);

            SwingUtilities.invokeLater(secondBrowser::initUI);
            Thread.sleep(3000);

            if (CefBrowserFrame.callbackCounter < 2) {
                throw new RuntimeException("Test FAILED. callbackCounter: " + CefBrowserFrame.callbackCounter);
            }
            System.out.println("Test PASSED");
        } finally {
            firstBrowser.getBrowser().dispose();
            secondBrowser.getBrowser().dispose();
            JBCefApp.getInstance().getCefApp().dispose();
            SwingUtilities.invokeAndWait(firstBrowser::dispose);
            SwingUtilities.invokeAndWait(secondBrowser::dispose);
        }
    }
}


class CefBrowserFrame extends JFrame {

    static volatile int callbackCounter;
    static volatile int queryCounter;

    private JBCefBrowser browser = new JBCefBrowser();

    public void initUI() {
        queryCounter++;
        CefMessageRouter.CefMessageRouterConfig config = new org.cef.browser.CefMessageRouter.CefMessageRouterConfig();
        config.jsQueryFunction = "cef_query_" + queryCounter;
        config.jsCancelFunction = "cef_query_cancel_" + queryCounter;
        CefMessageRouter msgRouter = CefMessageRouter.create(config);

        msgRouter.addHandler(new CefMessageRouterHandlerAdapter(){
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long query_id, String request,
                                   boolean persistent, CefQueryCallback callback) {
                System.out.println(request);
                callbackCounter++;
                System.out.println(callbackCounter);
                return true;
            }
        }, true);

        browser.getCefClient().addMessageRouter(msgRouter);

        browser.getCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                System.out.println("onLoadEnd " + browser.getURL());
                String jsFunc = "cef_query_" + queryCounter;
                String jsQuery = "window." + jsFunc + "({request: '" + jsFunc + "'});";
                browser.executeJavaScript(jsQuery, "", 0);
            }
        });

        getContentPane().add(browser.getCefBrowser().getUIComponent());
        setSize(640, 480);
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
}