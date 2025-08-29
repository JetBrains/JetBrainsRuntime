// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @key headful
 * @summary Tests that JCEF starts and loads empty page with no crash
 * @author Anton Tarasov
 * @run main JCEFStartupTest
 */
public class JCEFStartupTest {
    static CefClient CEF_CLIENT = JBCefApp.getInstance().createClient();

    static final CountDownLatch LATCH = new CountDownLatch(1);
    static volatile boolean PASSED;

    JCEFStartupTest() {
        JFrame frame = new JFrame("JCEF");

        CEF_CLIENT.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame cefFrame, CefRequest.TransitionType transitionType) {
                System.out.println("onLoadStart");
            }
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame cefFrame, int i) {
                System.out.println("onLoadEnd");

//                cefBrowser.close(false); <-- todo: makes jtreg fail on exit code
                PASSED = true;
                LATCH.countDown();
            }
            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame cefFrame, ErrorCode errorCode, String s, String s1) {
                System.out.println("onLoadError");
            }
        });

        CefBrowser browser = CEF_CLIENT.createBrowser("about:blank", false, false);
        frame.add(browser.getUIComponent());

        frame.setSize(640, 480);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(JCEFStartupTest::new);

        try {
            LATCH.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        CEF_CLIENT.dispose();
        JBCefApp.getInstance().getCefApp().dispose();

        if (!PASSED) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }
}
