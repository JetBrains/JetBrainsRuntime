// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

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
    static final CountDownLatch LATCH = new CountDownLatch(1);
    static volatile boolean PASSED;

    static volatile JBCefBrowser BROWSER;

    JCEFStartupTest() {
        JFrame frame = new JFrame("JCEF");
        BROWSER = new JBCefBrowser();

        BROWSER.getCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser cefBrowser, CefFrame cefFrame, CefRequest.TransitionType transitionType) {
                System.out.println("onLoadStart");
            }
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame cefFrame, int i) {
                System.out.println("onLoadEnd");
                PASSED = true;
                LATCH.countDown();
            }
            @Override
            public void onLoadError(CefBrowser cefBrowser, CefFrame cefFrame, ErrorCode errorCode, String s, String s1) {
                System.out.println("onLoadError");
            }
        });

        frame.add(BROWSER.getComponent());

        frame.setSize(640, 480);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    /**
     * Pass "cmd-q" to manually reproduce JBR-2222.
     */
    public static void main(String[] args) {
        EventQueue.invokeLater(JCEFStartupTest::new);

        try {
            LATCH.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (!(args.length > 0 && args[0].equalsIgnoreCase("cmd-q"))) {
            BROWSER.dispose();
        }
        JBCefApp.getInstance().getCefApp().dispose();

        if (!PASSED) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }
}
